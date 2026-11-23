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
    @Volatile private var cachedVisitorData: String? = null
    @Volatile private var visitorDataFetchedAtMs: Long = 0L
    private val visitorLock = Any()

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

    /**
     * Sugerencias en vivo al escribir en el buscador (0 cookies, respuesta acotada).
     */
    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim().take(120)
        if (q.length < 2) return@withContext emptyList()
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("input", q)
        }
        val root = postWebRemix("music/get_search_suggestions", payload, useAuth = false)
            ?: return@withContext emptyList()
        val contents = root.arr("contents") ?: return@withContext emptyList()
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (sec in contents) {
            val items = sec.asObj()?.obj("searchSuggestionsSectionRenderer")?.arr("contents") ?: continue
            for (item in items) {
                val sr = item.asObj()?.obj("searchSuggestionRenderer") ?: continue
                val txt = runsText(sr.obj("suggestion"))
                    .replace(Regex("[\\r\\n\\u0000-\\u001F]"), "")
                    .trim()
                    .take(100)
                if (txt.isNotEmpty() && seen.add(txt.lowercase())) {
                    out.add(txt)
                    if (out.size >= 7) return@withContext out
                }
            }
        }
        out
    }

    /**
     * Resuelve título, artista, duración y carátula a partir de un videoId compartido (Compartir -> Brook).
     */
    suspend fun fetchTrackByVideoId(videoId: String): Track? = withContext(Dispatchers.IO) {
        if (!SecurityValidators.isValidVideoId(videoId)) return@withContext null
        val firstVd = getOrFetchVisitorData(forceRefresh = false)
        fetchTrackDetailsWithVd(videoId, firstVd)?.let { return@withContext it }
        val freshVd = getOrFetchVisitorData(forceRefresh = true)
        if (freshVd != null && freshVd != firstVd) {
            return@withContext fetchTrackDetailsWithVd(videoId, freshVd)
        }
        null
    }

    private fun fetchTrackDetailsWithVd(videoId: String, vd: String?): Track? {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "VISIONOS")
                    put("clientVersion", VISIONOS_CLIENT_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS")
                    put("osVersion", "26.5.23O471")
                    put("hl", "es")
                    put("gl", "ES")
                    if (!vd.isNullOrBlank()) put("visitorData", vd)
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", VISIONOS_UA)
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "101")
            .header("X-YouTube-Client-Version", VISIONOS_CLIENT_VERSION)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (!vd.isNullOrBlank()) reqBuilder.header("X-Goog-Visitor-Id", vd)

        return try {
            apiHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyTxt = readBoundedBody(resp) ?: return null
                val root = json.parseToJsonElement(bodyTxt).jsonObject
                val details = root.obj("videoDetails") ?: return null
                val title = details.str("title").orEmpty().ifBlank { "Canción de YouTube" }
                val rawAuthor = details.str("author").orEmpty()
                val artist = rawAuthor.removeSuffix(" - Topic").trim().ifBlank { "YouTube Music" }
                val durSec = details.str("lengthSeconds")?.toIntOrNull()?.coerceIn(0, 86_400) ?: 0
                val thumb = lastThumbUrl(details.obj("thumbnail")?.arr("thumbnails"))
                    .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
                SecurityValidators.sanitizeTrack(
                    Track(
                        id = videoId,
                        title = title,
                        artist = artist,
                        duration = durSec,
                        durationText = if (durSec > 0) SecurityValidators.fmtDuration(durSec) else "",
                        thumb = thumb,
                    )
                )
            }
        } catch (_: Exception) {
            null
        }
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

    private fun getOrFetchVisitorData(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cur = cachedVisitorData
            if (!cur.isNullOrBlank() && (now - visitorDataFetchedAtMs) < VISITOR_TTL_MS) {
                return cur
            }
        }
        synchronized(visitorLock) {
            val now2 = System.currentTimeMillis()
            if (!forceRefresh) {
                val cur = cachedVisitorData
                if (!cur.isNullOrBlank() && (now2 - visitorDataFetchedAtMs) < VISITOR_TTL_MS) {
                    return cur
                }
            }
            val payload = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "VISIONOS")
                        put("clientVersion", VISIONOS_CLIENT_VERSION)
                        put("hl", "es")
                        put("gl", "ES")
                    }
                }
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("User-Agent", VISIONOS_UA)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return try {
                apiHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return cachedVisitorData
                    val txt = readBoundedBody(resp, maxBytes = 256L * 1024L) ?: return cachedVisitorData
                    val root = json.parseToJsonElement(txt).jsonObject
                    val vd = root.obj("responseContext")?.str("visitorData")?.takeIf { it.length in 10..2048 }
                    if (vd != null) {
                        cachedVisitorData = vd
                        visitorDataFetchedAtMs = System.currentTimeMillis()
                    }
                    vd ?: cachedVisitorData
                }
            } catch (_: Exception) {
                cachedVisitorData
            }
        }
    }

    fun getCachedStream(videoId: String, preferHls: Boolean = false): ResolvedStream? {
        if (!SecurityValidators.isValidVideoId(videoId)) return null
        val cacheKey = if (preferHls) "$videoId:hls" else videoId
        val cached = streamCache[cacheKey] ?: return null
        return if ((System.currentTimeMillis() - cached.fetchedAtMs) < STREAM_CACHE_TTL_MS) cached else null
    }

    fun invalidateCachedStream(videoId: String) {
        streamCache.remove(videoId)
        streamCache.remove("$videoId:hls")
    }

    /**
     * Resuelve el stream de audio directo de máxima calidad gratis (Opus 160k itag 251 -> AAC 130k itag 140)
     * con fallback automático a HLS solo-audio (itag 234 AAC 128k).
     * Se ejecuta SIN cookies de cuenta para garantizar 0 anuncios y máxima seguridad de tu cuenta Google.
     */
    suspend fun resolveStreamUrl(
        videoId: String,
        forceRefresh: Boolean = false,
        preferHls: Boolean = false,
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        resolveStreamUrlBlocking(videoId, forceRefresh, preferHls)
    }

    /**
     * Versión bloqueante usada directamente por `ResolvingDataSource` en el hilo Loader de ExoPlayer
     * para transiciones sin cortes en segundo plano (sin salir nunca de Foreground ni entrar en STATE_ENDED).
     */
    fun resolveStreamUrlBlocking(
        videoId: String,
        forceRefresh: Boolean = false,
        preferHls: Boolean = false,
    ): ResolvedStream? {
        if (!SecurityValidators.isValidVideoId(videoId)) return null

        val cacheKey = if (preferHls) "$videoId:hls" else videoId
        if (!forceRefresh) {
            getCachedStream(videoId, preferHls)?.let { return it }
        } else {
            invalidateCachedStream(videoId)
        }
        if (Thread.currentThread().isInterrupted) return null

        // 1. Cliente principal VISIONOS + visitorData fresco (devuelve Opus 251 completo sin 403 + HLS audio 234)
        val firstVd = getOrFetchVisitorData(forceRefresh = forceRefresh)
        if (Thread.currentThread().isInterrupted) return null
        var resolved = fetchPlayerStream(videoId, useVisionOs = true, visitorData = firstVd, preferHls = preferHls)

        // Si falló con visitorData cacheado, renovar visitorData una vez y reintentar
        if (resolved == null && !forceRefresh && !Thread.currentThread().isInterrupted) {
            val freshVd = getOrFetchVisitorData(forceRefresh = true)
            if (freshVd != null && freshVd != firstVd && !Thread.currentThread().isInterrupted) {
                resolved = fetchPlayerStream(videoId, useVisionOs = true, visitorData = freshVd, preferHls = preferHls)
            }
        }

        // 2. Fallback secundario IOS (útil para algunos directos o pistas con HLS en iOS)
        if (resolved == null && !Thread.currentThread().isInterrupted) {
            val vd = cachedVisitorData
            resolved = fetchPlayerStream(videoId, useVisionOs = false, visitorData = vd, preferHls = true)
        }

        if (resolved != null) {
            if (streamCache.size > 60) streamCache.clear()
            streamCache[cacheKey] = resolved
        }
        return resolved
    }

    private fun fetchPlayerStream(
        videoId: String,
        useVisionOs: Boolean,
        visitorData: String?,
        preferHls: Boolean,
    ): ResolvedStream? {
        val ua = if (useVisionOs) VISIONOS_UA else IOS_UA
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    if (useVisionOs) {
                        put("clientName", "VISIONOS")
                        put("clientVersion", VISIONOS_CLIENT_VERSION)
                        put("deviceMake", "Apple")
                        put("deviceModel", "RealityDevice17,1")
                        put("osName", "visionOS")
                        put("osVersion", "26.5.23O471")
                    } else {
                        put("clientName", "IOS")
                        put("clientVersion", IOS_CLIENT_VERSION)
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iPhone")
                        put("osVersion", "18.3.2.22D82")
                    }
                    put("hl", "es")
                    put("gl", "ES")
                    if (!visitorData.isNullOrBlank()) {
                        put("visitorData", visitorData)
                    }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", ua)
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", if (useVisionOs) "101" else "5")
            .header("X-YouTube-Client-Version", if (useVisionOs) VISIONOS_CLIENT_VERSION else IOS_CLIENT_VERSION)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        return try {
            apiHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyTxt = readBoundedBody(resp) ?: return null
                val root = json.parseToJsonElement(bodyTxt).jsonObject
                val status = root.obj("playabilityStatus")?.str("status")
                if (status != "OK") return null

                val streamingData = root.obj("streamingData") ?: return null
                val hlsMasterUrl = streamingData.str("hlsManifestUrl")
                    ?.takeIf { SecurityValidators.isSafeStreamUrl(it) }

                // Si se solicitó HLS (reintento automático o fallback), priorizar pista HLS solo-audio (234/233)
                if (preferHls && hlsMasterUrl != null) {
                    val hlsAudio = resolveAudioOnlyHls(hlsMasterUrl, ua)
                    if (hlsAudio != null) return hlsAudio
                }

                val adaptive = streamingData.arr("adaptiveFormats")
                val candidates = adaptive?.mapNotNull { el ->
                    val o = el.asObj() ?: return@mapNotNull null
                    val mime = o.str("mimeType") ?: return@mapNotNull null
                    if (!mime.startsWith("audio/")) return@mapNotNull null
                    val url = o.str("url") ?: return@mapNotNull null
                    if (!SecurityValidators.isSafeStreamUrl(url)) return@mapNotNull null
                    val bitrate = o.int("bitrate") ?: 0
                    val itag = o.int("itag") ?: 0
                    Triple(itag, bitrate, Pair(url, mime))
                }.orEmpty()

                val bestOpus = candidates
                    .filter { it.third.second.contains("opus", ignoreCase = true) }
                    .maxByOrNull { it.second }
                val bestAny = candidates.maxByOrNull { it.second }
                val chosen = bestOpus ?: bestAny

                if (chosen != null) {
                    val kbps = (chosen.second / 1000).coerceAtLeast(64)
                    val codecName = if (chosen.third.second.contains("opus", ignoreCase = true)) "OPUS" else "AAC"
                    ResolvedStream(
                        url = chosen.third.first,
                        mimeType = chosen.third.second,
                        bitrateKbps = kbps,
                        qualityLabel = "$codecName ${kbps}k",
                        userAgent = ua,
                    )
                } else if (hlsMasterUrl != null) {
                    resolveAudioOnlyHls(hlsMasterUrl, ua)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extrae la lista HLS solo-audio (GROUP-ID="234" AAC 128k, o "233" como respaldo)
     * desde el manifiesto maestro HLS para reproducir 0 bytes de vídeo en ExoPlayer.
     */
    private fun resolveAudioOnlyHls(masterUrl: String, userAgent: String): ResolvedStream? {
        if (!SecurityValidators.isSafeStreamUrl(masterUrl)) return null
        return try {
            val req = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", userAgent)
                .get()
                .build()
            streamHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return ResolvedStream(
                        url = masterUrl,
                        mimeType = "application/x-mpegURL",
                        bitrateKbps = 128,
                        qualityLabel = "HLS AUDIO",
                        userAgent = userAgent,
                    )
                }
                val text = readBoundedBody(resp, maxBytes = 512L * 1024L).orEmpty()
                val audioLines = text.lineSequence()
                    .filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") }
                    .toList()

                var best234: String? = null
                var best233: String? = null
                var anyAudio: String? = null

                for (line in audioLines) {
                    val uri = HLS_URI_RE.find(line)?.groupValues?.getOrNull(1) ?: continue
                    if (!SecurityValidators.isSafeStreamUrl(uri)) continue
                    val gid = HLS_GROUP_RE.find(line)?.groupValues?.getOrNull(1)
                    when (gid) {
                        "234" -> if (best234 == null) best234 = uri
                        "233" -> if (best233 == null) best233 = uri
                    }
                    if (anyAudio == null) anyAudio = uri
                }

                when {
                    best234 != null -> ResolvedStream(
                        url = best234,
                        mimeType = "application/x-mpegURL",
                        bitrateKbps = 128,
                        qualityLabel = "HLS AAC 128k",
                        userAgent = userAgent,
                    )
                    best233 != null -> ResolvedStream(
                        url = best233,
                        mimeType = "application/x-mpegURL",
                        bitrateKbps = 64,
                        qualityLabel = "HLS AAC 64k",
                        userAgent = userAgent,
                    )
                    anyAudio != null -> ResolvedStream(
                        url = anyAudio,
                        mimeType = "application/x-mpegURL",
                        bitrateKbps = 96,
                        qualityLabel = "HLS AUDIO",
                        userAgent = userAgent,
                    )
                    else -> ResolvedStream(
                        url = masterUrl,
                        mimeType = "application/x-mpegURL",
                        bitrateKbps = 128,
                        qualityLabel = "HLS LIVE",
                        userAgent = userAgent,
                    )
                }
            }
        } catch (_: Exception) {
            ResolvedStream(
                url = masterUrl,
                mimeType = "application/x-mpegURL",
                bitrateKbps = 128,
                qualityLabel = "HLS AUDIO",
                userAgent = userAgent,
            )
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
        fetchPlaylistWithTitle(rawBrowseOrListId, useAuth).second

    suspend fun fetchPlaylistWithTitle(rawBrowseOrListId: String, useAuth: Boolean = true): Pair<String, List<Track>> =
        withContext(Dispatchers.IO) {
            val cleanId = rawBrowseOrListId.trim()
            if (!SecurityValidators.isValidBrowseId(cleanId)) return@withContext ("Playlist YT" to emptyList())
            val browseId = if (cleanId.startsWith("VL") || cleanId.startsWith("FE")) cleanId else "VL$cleanId"

            val payload = buildJsonObject {
                put("context", webRemixContext())
                put("browseId", browseId)
            }
            val root = postWebRemix("browse", payload, useAuth = useAuth && authStore.isLoggedIn())
                ?: return@withContext ("Playlist YT" to emptyList())

            val headerTitle = findPlaylistTitle(root).ifBlank { "Playlist YT" }
            val tracks = mutableListOf<Track>()
            val seen = HashSet<String>()
            collectResponsiveTracks(root, tracks, seen, limit = 150, depth = 0)
            headerTitle to tracks
        }

    private fun findPlaylistTitle(root: JsonObject): String {
        val hdr = root.obj("header")
        val t1 = runsText(hdr?.obj("musicDetailHeaderRenderer")?.obj("title"))
        if (t1.isNotBlank()) return t1.take(36)
        val t2 = runsText(hdr?.obj("musicEditablePlaylistDetailHeaderRenderer")
            ?.obj("header")?.obj("musicDetailHeaderRenderer")?.obj("title"))
        if (t2.isNotBlank()) return t2.take(36)
        val t3 = runsText(
            root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")
                ?.arr("tabs")?.firstOrNull()?.asObj()?.obj("tabRenderer")?.obj("content")
                ?.obj("sectionListRenderer")?.arr("contents")?.firstOrNull()?.asObj()
                ?.obj("musicResponsiveHeaderRenderer")?.obj("title")
        )
        return t3.take(36)
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

    // ─── PÁGINA REAL DE ARTISTA Y ÁLBUM (TOP CANCIONES, ÁLBUMES, SENCILLOS, ARTISTAS SIMILARES) ───
    suspend fun resolveArtistBrowseId(artistName: String): String? = withContext(Dispatchers.IO) {
        val clean = artistName.substringBefore(",").substringBefore(" & ").substringBefore(" feat.").trim().take(120)
        if (clean.isEmpty()) return@withContext null
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("query", clean)
            put("params", ARTISTS_FILTER_PARAMS)
        }
        val root = postWebRemix("search", payload, useAuth = false) ?: return@withContext null
        findFirstArtistBrowseId(root, depth = 0)
    }

    private fun findFirstArtistBrowseId(node: JsonElement, depth: Int): String? {
        if (depth > MAX_JSON_DEPTH) return null
        when (node) {
            is JsonObject -> {
                val li = node.obj("musicResponsiveListItemRenderer")
                if (li != null) {
                    val bid = li.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
                    if (bid != null && bid.startsWith("UC") && SecurityValidators.isValidBrowseId(bid)) {
                        return bid
                    }
                }
                for (v in node.values) {
                    val found = findFirstArtistBrowseId(v, depth + 1)
                    if (found != null) return found
                }
            }
            is JsonArray -> {
                for (el in node) {
                    val found = findFirstArtistBrowseId(el, depth + 1)
                    if (found != null) return found
                }
            }
            else -> Unit
        }
        return null
    }

    suspend fun fetchArtistPage(nameOrBrowseId: String, fallbackName: String = ""): ArtistPageData? =
        withContext(Dispatchers.IO) {
            val trimmed = nameOrBrowseId.trim()
            if (trimmed.isEmpty()) return@withContext null
            val browseId = if (trimmed.startsWith("UC") && SecurityValidators.isValidBrowseId(trimmed)) {
                trimmed
            } else {
                resolveArtistBrowseId(trimmed)
            }
            val displayFallback = fallbackName.ifBlank { if (trimmed.startsWith("UC")) "Artista" else trimmed }

            if (browseId == null) {
                // Respaldo elegante si no se resolvió canal UC: crear ficha desde búsqueda directa
                val fallbackSongs = search(displayFallback, limit = 15, mode = "artist")
                if (fallbackSongs.isEmpty()) return@withContext null
                return@withContext ArtistPageData(
                    browseId = "",
                    name = displayFallback,
                    bannerThumb = fallbackSongs.first().thumb,
                    topSongs = fallbackSongs,
                )
            }

            val payload = buildJsonObject {
                put("context", webRemixContext())
                put("browseId", browseId)
            }
            val root = postWebRemix("browse", payload, useAuth = false)
            if (root == null) {
                val fallbackSongs = search(displayFallback, limit = 15, mode = "artist")
                if (fallbackSongs.isEmpty()) return@withContext null
                return@withContext ArtistPageData(
                    browseId = browseId,
                    name = displayFallback,
                    bannerThumb = fallbackSongs.first().thumb,
                    topSongs = fallbackSongs,
                )
            }

            val hdr = root.obj("header")?.obj("musicImmersiveHeaderRenderer")
                ?: root.obj("header")?.obj("musicVisualHeaderRenderer")
            val name = runsText(hdr?.obj("title")).ifBlank { displayFallback }.take(120)
            val description = runsText(hdr?.obj("description")).take(400)
            val subscribers = runsText(
                hdr?.obj("subscriptionButton")?.obj("subscribeButtonRenderer")?.obj("subscriberCountText")
            ).ifBlank {
                runsText(hdr?.obj("subscriptionButton")?.obj("subscribeButtonRenderer")?.obj("longSubscriberCountText"))
            }.take(60)
            val bannerThumb = SecurityValidators.upgradeThumbUrl(
                lastThumbUrl(hdr?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails"))
                    .ifBlank { lastThumbUrl(hdr?.obj("foregroundThumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails")) }
            )

            val topSongs = mutableListOf<Track>()
            val seenSongs = HashSet<String>()
            val albums = mutableListOf<ArtistAlbumItem>()
            val singles = mutableListOf<ArtistAlbumItem>()
            val seenAlbums = HashSet<String>()
            val related = mutableListOf<RelatedArtistItem>()
            val seenRelated = HashSet<String>()

            val sections = root.obj("contents")
                ?.obj("singleColumnBrowseResultsRenderer")
                ?.arr("tabs")?.firstOrNull()?.asObj()
                ?.obj("tabRenderer")?.obj("content")
                ?.obj("sectionListRenderer")?.arr("contents")
                .orEmpty()

            for (sec in sections) {
                val obj = sec.asObj() ?: continue
                // Top canciones en musicShelfRenderer
                val shelf = obj.obj("musicShelfRenderer")
                if (shelf != null) {
                    for (item in shelf.arr("contents").orEmpty()) {
                        val li = item.asObj()?.obj("musicResponsiveListItemRenderer") ?: continue
                        val tr = parseResponsiveItem(li)
                        if (tr != null && seenSongs.add(tr.id)) {
                            topSongs.add(
                                if (tr.artist == "YouTube Music") tr.copy(artist = name) else tr
                            )
                        }
                    }
                }
                // Álbumes, Sencillos y Artistas similares en musicCarouselShelfRenderer
                val carousel = obj.obj("musicCarouselShelfRenderer")
                if (carousel != null) {
                    val headerTitle = runsText(
                        carousel.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")
                    ).lowercase()
                    val isSinglesSection = headerTitle.contains("sencillo") || headerTitle.contains("single") || headerTitle.contains("ep")
                    for (cItem in carousel.arr("contents").orEmpty()) {
                        val twoRow = cItem.asObj()?.obj("musicTwoRowItemRenderer") ?: continue
                        val itemBrowseId = twoRow.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId") ?: continue
                        if (!SecurityValidators.isValidBrowseId(itemBrowseId)) continue
                        val itemTitle = runsText(twoRow.obj("title")).take(120)
                        if (itemTitle.isBlank()) continue
                        val itemSub = runsText(twoRow.obj("subtitle")).take(100)
                        val itemThumb = SecurityValidators.upgradeThumbUrl(
                            lastThumbUrl(
                                twoRow.obj("thumbnailRenderer")
                                    ?.obj("musicThumbnailRenderer")
                                    ?.obj("thumbnail")?.arr("thumbnails")
                            )
                        )
                        when {
                            itemBrowseId.startsWith("MPREb_") || itemBrowseId.startsWith("OLAK5uy_") -> {
                                if (seenAlbums.add(itemBrowseId)) {
                                    val entry = ArtistAlbumItem(
                                        browseId = itemBrowseId,
                                        title = itemTitle,
                                        year = itemSub,
                                        thumb = itemThumb,
                                        isSingle = isSinglesSection || itemSub.contains("single", ignoreCase = true) || itemSub.contains("sencillo", ignoreCase = true),
                                    )
                                    if (entry.isSingle) singles.add(entry) else albums.add(entry)
                                }
                            }
                            itemBrowseId.startsWith("UC") && itemBrowseId != browseId -> {
                                if (seenRelated.add(itemBrowseId)) {
                                    related.add(
                                        RelatedArtistItem(
                                            browseId = itemBrowseId,
                                            name = itemTitle,
                                            subtitle = itemSub,
                                            thumb = itemThumb,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (topSongs.size < 5) {
                val extra = search(name, limit = 12, mode = "artist")
                for (t in extra) {
                    if (seenSongs.add(t.id)) topSongs.add(t)
                }
            }

            ArtistPageData(
                browseId = browseId,
                name = name,
                subscribers = subscribers,
                description = description,
                bannerThumb = bannerThumb.ifBlank { topSongs.firstOrNull()?.thumb.orEmpty() },
                topSongs = topSongs.take(20),
                albums = albums.take(15),
                singles = singles.take(15),
                relatedArtists = related.take(12),
            )
        }

    suspend fun fetchAlbumPage(
        browseId: String,
        fallbackTitle: String = "Álbum",
        fallbackArtist: String = "",
        fallbackThumb: String = "",
    ): AlbumPageData? = withContext(Dispatchers.IO) {
        val cleanId = browseId.trim()
        if (!SecurityValidators.isValidBrowseId(cleanId)) return@withContext null
        val payload = buildJsonObject {
            put("context", webRemixContext())
            put("browseId", cleanId)
        }
        val root = postWebRemix("browse", payload, useAuth = false) ?: return@withContext null

        val respHdr = root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")
            ?.arr("tabs")?.firstOrNull()?.asObj()?.obj("tabRenderer")?.obj("content")
            ?.obj("sectionListRenderer")?.arr("contents")?.firstOrNull()?.asObj()
            ?.obj("musicResponsiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicDetailHeaderRenderer")

        val title = runsText(respHdr?.obj("title")).ifBlank { fallbackTitle }.take(120)
        val sub = runsText(respHdr?.obj("subtitle")).take(120)
        val strap = runsText(respHdr?.obj("straplineTextOne")).ifBlank { fallbackArtist }.take(120)
        val thumb = SecurityValidators.upgradeThumbUrl(
            lastThumbUrl(respHdr?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails"))
        ).ifBlank { SecurityValidators.upgradeThumbUrl(fallbackThumb) }

        val rawTracks = mutableListOf<Track>()
        val seen = HashSet<String>()
        collectResponsiveTracks(root, rawTracks, seen, limit = 80, depth = 0)
        val enriched = rawTracks.map { t ->
            t.copy(
                artist = if (t.artist == "YouTube Music" && strap.isNotBlank()) strap else t.artist,
                album = title,
                thumb = t.thumb.ifBlank { thumb },
            )
        }
        AlbumPageData(
            browseId = cleanId,
            title = title,
            artist = strap.ifBlank { enriched.firstOrNull()?.artist.orEmpty() },
            year = sub,
            thumb = thumb,
            tracks = enriched,
        )
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
        private val ALLOWED_ENDPOINTS = setOf("search", "next", "browse", "music/get_search_suggestions")
        private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val ARTISTS_FILTER_PARAMS = "EgWKAQIgAWoKEAkQChADEAUQBA%3D%3D"
        private const val STREAM_CACHE_TTL_MS = 15 * 60 * 1000L
        private const val VISITOR_TTL_MS = 25 * 60 * 1000L
        private const val MAX_API_JSON_BYTES = 4L * 1024L * 1024L
        private const val MAX_THUMB_BYTES = 3L * 1024L * 1024L
        private const val MAX_JSON_DEPTH = 32
        private val HLS_URI_RE = Regex("""URI="([^"]+)"""")
        private val HLS_GROUP_RE = Regex("""GROUP-ID="([^"]+)"""")
        private const val WEB_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val VISIONOS_CLIENT_VERSION = "1.02"
        const val VISIONOS_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
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
