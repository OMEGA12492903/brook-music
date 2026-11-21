package com.noctalia.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cliente nativo y endurecido de YouTube Music InnerTube:
 * - 3 clientes OkHttp aislados (API autenticada sin redirecciones, Streams ExoPlayer sin cookies,
 *   Carátulas Coil sin cookies) con interceptores de red por cada salto.
 * - Límite estricto de tamaño en respuestas JSON (4 MiB) para evitar OOM DoS.
 * - Extracción directa de audio Opus 160 kbps (itag 251) / AAC 130 kbps (itag 140) sin anuncios
 *   y SIN enviar cookies de tu cuenta Google.
 */
class YTMusicApi(private val authStore: AuthStore) {

    // 1. Cliente exclusivo para API JSON de YouTube Music (SIN redirecciones: impide fuga de cookies)
    private val apiHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            val u = req.url
            if (u.scheme != "https" || u.host !in ALLOWED_API_HOSTS) {
                throw IOException("Blocked non-YouTube API host: ${u.host}")
            }
            chain.proceed(req)
        })
        .build()

    // 2. Cliente exclusivo para streaming de audio en ExoPlayer (0 cookies, valida cada salto/redirección)
    val streamHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (!SecurityValidators.isSafeStreamUrl(req.url.toString())) {
                throw IOException("Blocked unsafe stream URL host: ${req.url.host}")
            }
            // Garantizar que jamás se filtren cookies o cabeceras de sesión al streaming
            val cleanReq = req.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Authorization")
                .build()
            chain.proceed(cleanReq)
        })
        .build()

    // 3. Cliente exclusivo para carátulas en Coil (0 cookies, valida cada salto/redirección + tope 3 MiB)
    val thumbHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (!SecurityValidators.isSafeThumbUrl(req.url.toString())) {
                throw IOException("Blocked unsafe thumbnail URL host: ${req.url.host}")
            }
            val cleanReq = req.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Authorization")
                .build()
            val resp = chain.proceed(cleanReq)
            val cl = resp.header("Content-Length")?.toLongOrNull()
            if (cl != null && cl > MAX_THUMB_BYTES) {
                resp.close()
                throw IOException("Oversized thumbnail rejected: $cl bytes")
            }
            resp
        })
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()

    private fun readBoundedBody(resp: Response, maxBytes: Long = MAX_API_JSON_BYTES): String? {
        val body = resp.body ?: return null
        val source = body.source()
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) return null
        return source.buffer.readUtf8()
    }

    private fun webRemixContext(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20250101.01.00")
            put("hl", "es")
            put("gl", "ES")
        }
    }

    private fun postWebRemix(endpoint: String, body: JsonObject, useAuth: Boolean = false): JsonObject? {
        if (endpoint !in ALLOWED_ENDPOINTS) return null
        val reqBuilder = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Origin", "https://music.youtube.com")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20250101.01.00")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (useAuth) {
            val cookie = authStore.getCookie()
            if (cookie != null) {
                val authHdr = authStore.buildSapisidHashAuthHeader(cookie)
                if (authHdr != null) {
                    reqBuilder.header("Cookie", cookie)
                    reqBuilder.header("Authorization", authHdr)
                    reqBuilder.header("X-Goog-AuthUser", "0")
                }
            }
        }

        return try {
            apiHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = readBoundedBody(resp) ?: return null
                json.parseToJsonElement(txt).jsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun search(query: String, limit: Int = 20, mode: String = "songs"): List<Track> = withContext(Dispatchers.IO) {
        val q = query.trim().take(180)
        if (q.isEmpty()) return@withContext emptyList()

        if (mode == "all") {
            return@withContext innerTubeSearch(q, null, limit)
        }
        val effectiveQuery = if (mode == "artist") "$q top songs" else q
        val songs = innerTubeSearch(effectiveQuery, SONGS_FILTER_PARAMS, limit)
        if (songs.size >= 5) return@withContext songs

        val extra = innerTubeSearch(effectiveQuery, null, limit)
        val merged = LinkedHashMap<String, Track>()
        songs.forEach { merged[it.id] = it }
        extra.forEach { if (!merged.containsKey(it.id) && merged.size < limit) merged[it.id] = it }
        merged.values.toList()
    }

    private fun innerTubeSearch(query: String, params: String?, limit: Int): List<Track> {
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("query", query)
            if (params != null) put("params", params)
        }
        val root = postWebRemix("search", payload, useAuth = false) ?: return emptyList()
        val tabs = root.obj("contents")?.obj("tabbedSearchResultsRenderer")?.arr("tabs") ?: return emptyList()
        val sections = tabs.firstOrNull()?.asObj()
            ?.obj("tabRenderer")?.obj("content")
            ?.obj("sectionListRenderer")?.arr("contents") ?: return emptyList()

        val results = mutableListOf<Track>()
        val seen = HashSet<String>()
        for (sec in sections) {
            val shelf = sec.asObj()?.obj("musicShelfRenderer") ?: continue
            val contents = shelf.arr("contents") ?: continue
            for (c in contents) {
                val li = c.asObj()?.obj("musicResponsiveListItemRenderer") ?: continue
                val t = parseResponsiveItem(li)
                if (t != null && seen.add(t.id)) {
                    results.add(t)
                    if (results.size >= limit) return results
                }
            }
        }
        return results
    }

    suspend fun getRadio(videoId: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        if (!SecurityValidators.isValidVideoId(videoId)) return@withContext emptyList()
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        val root = postWebRemix("next", payload, useAuth = false) ?: return@withContext emptyList()
        val tabs = root.obj("contents")
            ?.obj("singleColumnMusicWatchNextResultsRenderer")
            ?.obj("tabbedRenderer")
            ?.obj("watchNextTabbedResultsRenderer")
            ?.arr("tabs") ?: return@withContext emptyList()

        val plContents = tabs.firstOrNull()?.asObj()
            ?.obj("tabRenderer")?.obj("content")
            ?.obj("musicQueueRenderer")?.obj("content")
            ?.obj("playlistPanelRenderer")?.arr("contents") ?: return@withContext emptyList()

        val out = mutableListOf<Track>()
        val seen = hashSetOf(videoId)
        for (el in plContents) {
            val vr = el.asObj()?.obj("playlistPanelVideoRenderer") ?: continue
            val vid = vr.str("videoId") ?: continue
            if (!SecurityValidators.isValidVideoId(vid) || !seen.add(vid)) continue
            val title = runsText(vr.obj("title"))
            val artist = runsText(vr.obj("shortBylineText")).ifEmpty { runsText(vr.obj("longBylineText")) }
            val durText = runsText(vr.obj("lengthText"))
            val thumb = lastThumbUrl(vr.obj("thumbnail")?.arr("thumbnails"))
            val track = SecurityValidators.sanitizeTrack(
                Track(
                    id = vid,
                    title = title,
                    artist = artist.ifEmpty { "YouTube Music" },
                    duration = SecurityValidators.parseDurationSec(durText),
                    durationText = durText,
                    thumb = thumb,
                )
            )
            if (track != null) {
                out.add(track)
                if (out.size >= limit) break
            }
        }
        out
    }

    /**
     * Resuelve el stream de audio directo de máxima calidad gratis (Opus 160k itag 251 -> AAC 130k itag 140).
     * Se ejecuta SIN cookies de cuenta para garantizar 0 anuncios y máxima seguridad de tu cuenta Google.
     */
    suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean = false): ResolvedStream? =
        withContext(Dispatchers.IO) {
            if (!SecurityValidators.isValidVideoId(videoId)) return@withContext null

            if (!forceRefresh) {
                val cached = streamCache[videoId]
                if (cached != null && (System.currentTimeMillis() - cached.fetchedAtMs) < STREAM_CACHE_TTL_MS) {
                    return@withContext cached
                }
            }

            val payload = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "IOS")
                        put("clientVersion", IOS_CLIENT_VERSION)
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iPhone")
                        put("osVersion", "18.3.2.22D82")
                        put("hl", "es")
                        put("gl", "ES")
                    }
                }
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", IOS_UA)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                apiHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bodyTxt = readBoundedBody(resp) ?: return@withContext null
                    val root = json.parseToJsonElement(bodyTxt).jsonObject
                    val status = root.obj("playabilityStatus")?.str("status")
                    if (status != "OK") return@withContext null

                    val adaptive = root.obj("streamingData")?.arr("adaptiveFormats") ?: return@withContext null
                    val candidates = adaptive.mapNotNull { el ->
                        val o = el.asObj() ?: return@mapNotNull null
                        val mime = o.str("mimeType") ?: return@mapNotNull null
                        if (!mime.startsWith("audio/")) return@mapNotNull null
                        val url = o.str("url") ?: return@mapNotNull null
                        if (!SecurityValidators.isSafeStreamUrl(url)) return@mapNotNull null
                        val bitrate = o.int("bitrate") ?: 0
                        val itag = o.int("itag") ?: 0
                        Triple(itag, bitrate, Pair(url, mime))
                    }

                    val bestOpus = candidates
                        .filter { it.third.second.contains("opus", ignoreCase = true) }
                        .maxByOrNull { it.second }
                    val bestAny = candidates.maxByOrNull { it.second }
                    val chosen = bestOpus ?: bestAny ?: return@withContext null

                    val kbps = (chosen.second / 1000).coerceAtLeast(64)
                    val codecName = if (chosen.third.second.contains("opus", ignoreCase = true)) "OPUS" else "AAC"
                    val resolved = ResolvedStream(
                        url = chosen.third.first,
                        mimeType = chosen.third.second,
                        bitrateKbps = kbps,
                        qualityLabel = "$codecName ${kbps}k",
                        userAgent = IOS_UA,
                    )
                    if (streamCache.size > 60) streamCache.clear()
                    streamCache[videoId] = resolved
                    resolved
                }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun fetchAccountPlaylists(): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        if (!authStore.isLoggedIn()) return@withContext emptyList()
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("browseId", "FEmusic_liked_playlists")
        }
        val root = postWebRemix("browse", payload, useAuth = true) ?: return@withContext emptyList()
        val results = mutableListOf<RemotePlaylist>()
        val seen = HashSet<String>()

        results.add(
            RemotePlaylist(
                browseId = "VLLM",
                title = "Música que me gusta",
                subtitle = "Tus Me gusta de YouTube Music",
                thumb = "",
            )
        )
        seen.add("VLLM")

        collectTwoRowPlaylists(root, results, seen, depth = 0)
        results
    }

    suspend fun fetchPlaylistTracks(rawBrowseOrListId: String, useAuth: Boolean = true): List<Track> =
        withContext(Dispatchers.IO) {
            val cleanId = rawBrowseOrListId.trim()
            if (!SecurityValidators.isValidBrowseId(cleanId)) return@withContext emptyList()
            val browseId = if (cleanId.startsWith("VL") || cleanId.startsWith("FE")) cleanId else "VL$cleanId"

            val payload = buildJsonObject {
                put("context", webRemixContext())
                put("browseId", browseId)
            }
            val root = postWebRemix("browse", payload, useAuth = useAuth && authStore.isLoggedIn())
                ?: return@withContext emptyList()

            val tracks = mutableListOf<Track>()
            val seen = HashSet<String>()
            collectResponsiveTracks(root, tracks, seen, limit = 150, depth = 0)
            tracks
        }

    suspend fun fetchAccountHistory(): List<Track> = withContext(Dispatchers.IO) {
        if (!authStore.isLoggedIn()) return@withContext emptyList()
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("browseId", "FEmusic_history")
        }
        val root = postWebRemix("browse", payload, useAuth = true) ?: return@withContext emptyList()
        val tracks = mutableListOf<Track>()
        val seen = HashSet<String>()
        collectResponsiveTracks(root, tracks, seen, limit = 40, depth = 0)
        tracks
    }

    private fun collectTwoRowPlaylists(
        node: JsonElement,
        out: MutableList<RemotePlaylist>,
        seen: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_JSON_DEPTH || out.size >= 80) return
        when (node) {
            is JsonObject -> {
                val twoRow = node.obj("musicTwoRowItemRenderer")
                if (twoRow != null) {
                    val browseId = twoRow.obj("navigationEndpoint")
                        ?.obj("browseEndpoint")?.str("browseId")
                    val title = runsText(twoRow.obj("title"))
                    val subtitle = runsText(twoRow.obj("subtitle"))
                    val thumb = lastThumbUrl(
                        twoRow.obj("thumbnailRenderer")
                            ?.obj("musicThumbnailRenderer")
                            ?.obj("thumbnail")?.arr("thumbnails")
                    )
                    if (browseId != null && SecurityValidators.isValidBrowseId(browseId) &&
                        title.isNotBlank() &&
                        (browseId.startsWith("VL") || browseId == "FEmusic_liked_videos") &&
                        seen.add(browseId)
                    ) {
                        out.add(
                            RemotePlaylist(
                                browseId = browseId,
                                title = title.take(120),
                                subtitle = subtitle.ifBlank { "Playlist de YouTube Music" }.take(120),
                                thumb = SecurityValidators.upgradeThumbUrl(thumb),
                            )
                        )
                    }
                }
                for (v in node.values) collectTwoRowPlaylists(v, out, seen, depth + 1)
            }
            is JsonArray -> {
                for (el in node) collectTwoRowPlaylists(el, out, seen, depth + 1)
            }
            else -> Unit
        }
    }

    private fun collectResponsiveTracks(
        node: JsonElement,
        out: MutableList<Track>,
        seen: MutableSet<String>,
        limit: Int,
        depth: Int,
    ) {
        if (depth > MAX_JSON_DEPTH || out.size >= limit) return
        when (node) {
            is JsonObject -> {
                val li = node.obj("musicResponsiveListItemRenderer")
                if (li != null) {
                    val t = parseResponsiveItem(li)
                    if (t != null && seen.add(t.id)) {
                        out.add(t)
                        if (out.size >= limit) return
                    }
                }
                for (v in node.values) {
                    collectResponsiveTracks(v, out, seen, limit, depth + 1)
                    if (out.size >= limit) return
                }
            }
            is JsonArray -> {
                for (el in node) {
                    collectResponsiveTracks(el, out, seen, limit, depth + 1)
                    if (out.size >= limit) return
                }
            }
            else -> Unit
        }
    }

    private fun parseResponsiveItem(li: JsonObject): Track? {
        val vid = li.obj("overlay")
            ?.obj("musicItemThumbnailOverlayRenderer")
            ?.obj("content")
            ?.obj("musicPlayButtonRenderer")
            ?.obj("playNavigationEndpoint")
            ?.obj("watchEndpoint")
            ?.str("videoId")
            ?: li.obj("playlistItemData")?.str("videoId")
            ?: return null

        if (!SecurityValidators.isValidVideoId(vid)) return null

        val cols = li.arr("flexColumns") ?: return null
        val title = cols.getOrNull(0)?.asObj()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")
            ?.obj("text")?.let { runsText(it) } ?: return null
        if (title.isBlank()) return null

        val subRuns = cols.getOrNull(1)?.asObj()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")
            ?.obj("text")?.arr("runs")

        val segments = mutableListOf<String>()
        val cur = StringBuilder()
        if (subRuns != null) {
            for (r in subRuns) {
                val raw = r.asObj()?.str("text") ?: continue
                if (raw.trim() == "•") {
                    if (cur.isNotEmpty()) {
                        segments.add(cur.toString().trim())
                        cur.clear()
                    }
                } else {
                    cur.append(raw)
                }
            }
            if (cur.isNotEmpty()) segments.add(cur.toString().trim())
        }

        if (segments.isNotEmpty() && segments[0].lowercase() in setOf("canción", "song", "vídeo", "video", "episodio")) {
            segments.removeAt(0)
        }

        var durText = ""
        var durSec = 0
        val durIdx = segments.indexOfLast { SecurityValidators.parseDurationSec(it) > 0 }
        if (durIdx >= 0) {
            durText = segments.removeAt(durIdx)
            durSec = SecurityValidators.parseDurationSec(durText)
        }

        if (durSec == 0) {
            li.arr("fixedColumns")?.forEach { fc ->
                val ftxt = fc.asObj()
                    ?.obj("musicResponsiveListItemFixedColumnRenderer")
                    ?.obj("text")?.let { runsText(it) } ?: ""
                val parsed = SecurityValidators.parseDurationSec(ftxt)
                if (parsed > 0) {
                    durText = ftxt
                    durSec = parsed
                }
            }
        }

        val artist = segments.getOrNull(0)?.ifBlank { "YouTube Music" } ?: "YouTube Music"
        val album = segments.getOrNull(1) ?: ""
        val thumb = lastThumbUrl(
            li.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails")
        )

        return SecurityValidators.sanitizeTrack(
            Track(
                id = vid,
                title = title,
                artist = artist,
                album = album,
                duration = durSec,
                durationText = durText,
                thumb = thumb,
            )
        )
    }

    private fun runsText(obj: JsonObject?): String {
        val runs = obj?.arr("runs") ?: return ""
        return runs.mapNotNull { it.asObj()?.str("text") }.joinToString("").trim()
    }

    private fun lastThumbUrl(arr: JsonArray?): String {
        val last = arr?.lastOrNull()?.asObj() ?: return ""
        return last.str("url") ?: ""
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ALLOWED_API_HOSTS = setOf("music.youtube.com", "www.youtube.com")
        private val ALLOWED_ENDPOINTS = setOf("search", "next", "browse")
        private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val STREAM_CACHE_TTL_MS = 15 * 60 * 1000L
        private const val MAX_API_JSON_BYTES = 4L * 1024L * 1024L
        private const val MAX_THUMB_BYTES = 3L * 1024L * 1024L
        private const val MAX_JSON_DEPTH = 32
        private const val WEB_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val IOS_CLIENT_VERSION = "20.10.4"
        const val IOS_UA =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    }
}

private fun JsonElement.asObj(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
