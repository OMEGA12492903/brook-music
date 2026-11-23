package com.noctalia.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class LyricsResult(
    val synced: List<LyricLine> = emptyList(),
    val plain: String = "",
    val source: String = "LRCLIB",
) {
    val hasLyrics: Boolean get() = synced.isNotEmpty() || plain.isNotBlank()
}

data class LyricSearchHit(
    val trackName: String,
    val artistName: String,
    val snippet: String = "",
)

data class LyricsTranslationResult(
    val detectedLang: String = "",
    val lines: List<String> = emptyList(),
    val alreadySpanish: Boolean = false,
)

/**
 * Cliente aislado y seguro para:
 * 1) Letras sincronizadas (https://lrclib.net, 0 cookies, tope 512 KiB).
 * 2) Búsqueda de canciones por trozo de letra en LRCLIB.
 * 3) Traducción en vivo de letras al español (https://translate.googleapis.com, cliente aislado sin cookies).
 */
class LyricsClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, LyricsResult>()
    private val translationCache = ConcurrentHashMap<String, LyricsTranslationResult>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.url.scheme != "https" || req.url.host != "lrclib.net") {
                throw IOException("Blocked non-LRCLIB host: ${req.url.host}")
            }
            chain.proceed(req.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
        })
        .build()

    private val translateClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.url.scheme != "https" || req.url.host != "translate.googleapis.com") {
                throw IOException("Blocked non-Translate host: ${req.url.host}")
            }
            chain.proceed(req.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
        })
        .build()

    suspend fun fetchLyrics(track: Track): LyricsResult = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext it }

        val cleanArtist = track.artist
            .substringBefore(",")
            .substringBefore(" y ")
            .substringBefore(" & ")
            .substringBefore(" feat.")
            .substringBefore(" ft.")
            .trim()
        val cleanTitle = track.title
            .replace(Regex("\\s*[\\(\\[](?:official|video|audio|lyric|visualizer|hd|4k).*?[\\)\\]]", RegexOption.IGNORE_CASE), "")
            .trim()

        val exact = queryExact(cleanTitle, cleanArtist, track.duration)
        if (exact != null && exact.hasLyrics) {
            putCache(track.id, exact)
            return@withContext exact
        }

        val searched = querySearch("$cleanTitle $cleanArtist")
        if (searched != null && searched.hasLyrics) {
            putCache(track.id, searched)
            return@withContext searched
        }

        LyricsResult()
    }

    /**
     * Busca canciones que contengan un fragmento de letra recordado por el usuario.
     */
    suspend fun searchTracksByLyricPhrase(phrase: String, limit: Int = 8): List<LyricSearchHit> =
        withContext(Dispatchers.IO) {
            val q = phrase.trim().take(140)
            if (q.length < 3) return@withContext emptyList()
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegments("api/search")
                .addQueryParameter("q", q)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Brook-Android/3.0")
                .get()
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val raw = readBounded(resp) ?: return@withContext emptyList()
                    val arr = json.parseToJsonElement(raw) as? JsonArray ?: return@withContext emptyList()
                    val out = mutableListOf<LyricSearchHit>()
                    val seen = HashSet<String>()
                    for (el in arr) {
                        val o = el as? JsonObject ?: continue
                        val name = o.str("trackName")?.trim().orEmpty()
                        val artist = o.str("artistName")?.trim().orEmpty()
                        if (name.isEmpty() || artist.isEmpty()) continue
                        val key = "${name.lowercase()}|${artist.lowercase()}"
                        if (!seen.add(key)) continue
                        val plain = o.str("plainLyrics").orEmpty()
                        val snippet = plain.lines().firstOrNull { it.contains(q, ignoreCase = true) }?.trim().orEmpty()
                        out.add(LyricSearchHit(trackName = name, artistName = artist, snippet = snippet))
                        if (out.size >= limit) break
                    }
                    out
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Traduce al español en vivo las líneas de letra manteniendo correspondencia exacta 1:1.
     * Detecta si la letra ya está en español para evitar traducciones redundantes.
     */
    suspend fun translateLyricsToSpanish(trackId: String, lines: List<String>): LyricsTranslationResult =
        withContext(Dispatchers.IO) {
            if (lines.isEmpty()) return@withContext LyricsTranslationResult()
            translationCache[trackId]?.let { return@withContext it }

            val cleanLines = lines.take(180).map { it.replace("\n", " ").trim() }
            val translatedOut = ArrayList<String>(cleanLines.size)
            var detectedLang = ""

            // Agrupar en lotes de ~1100 caracteres para minimizar peticiones manteniendo índices 1:1
            var cursor = 0
            while (cursor < cleanLines.size) {
                val batch = mutableListOf<String>()
                var chars = 0
                while (cursor < cleanLines.size && (batch.isEmpty() || chars + cleanLines[cursor].length < 1100) && batch.size < 25) {
                    val ln = cleanLines[cursor]
                    batch.add(ln)
                    chars += ln.length + 1
                    cursor++
                }
                val chunkRes = translateBatchToEs(batch)
                if (chunkRes == null) {
                    return@withContext LyricsTranslationResult()
                }
                if (detectedLang.isEmpty() && chunkRes.first.isNotEmpty()) {
                    detectedLang = chunkRes.first
                    if (detectedLang.equals("es", ignoreCase = true)) {
                        val res = LyricsTranslationResult(detectedLang = "es", lines = emptyList(), alreadySpanish = true)
                        putTranslationCache(trackId, res)
                        return@withContext res
                    }
                }
                translatedOut.addAll(chunkRes.second)
            }

            val res = LyricsTranslationResult(
                detectedLang = detectedLang.ifEmpty { "auto" },
                lines = translatedOut,
                alreadySpanish = false,
            )
            putTranslationCache(trackId, res)
            res
        }

    private fun translateBatchToEs(batch: List<String>): Pair<String, List<String>>? {
        val joined = batch.joinToString("\n")
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("translate.googleapis.com")
            .addPathSegments("translate_a/single")
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", "es")
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", joined)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .get()
            .build()
        return try {
            translateClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = readBounded(resp, maxBytes = 256L * 1024L) ?: return null
                val root = json.parseToJsonElement(raw) as? JsonArray ?: return null
                val detected = (root.getOrNull(2) as? JsonPrimitive)?.contentOrNull.orEmpty()
                val segs = root.getOrNull(0) as? JsonArray ?: return null
                val sb = StringBuilder()
                for (el in segs) {
                    val pair = el as? JsonArray ?: continue
                    val piece = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: continue
                    sb.append(piece)
                }
                val splitLines = sb.toString().split("\n")
                val aligned = List(batch.size) { idx ->
                    splitLines.getOrNull(idx)?.trim().orEmpty()
                }
                detected to aligned
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryExact(title: String, artist: String, durationSec: Int): LyricsResult? {
        if (title.isBlank() || artist.isBlank()) return null
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
        if (durationSec > 0) {
            urlBuilder.addQueryParameter("duration", durationSec.toString())
        }
        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "Brook-Android/3.0")
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = readBounded(resp) ?: return null
                parseLrclibObject(json.parseToJsonElement(raw).jsonObject)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun querySearch(query: String): LyricsResult? {
        if (query.isBlank()) return null
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/search")
            .addQueryParameter("q", query.take(120))
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Brook-Android/3.0")
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = readBounded(resp) ?: return null
                val arr = json.parseToJsonElement(raw) as? JsonArray ?: return null
                val objs = arr.mapNotNull { it as? JsonObject }
                val bestSynced = objs.firstOrNull { !it.str("syncedLyrics").isNullOrBlank() }
                val bestAny = bestSynced ?: objs.firstOrNull { !it.str("plainLyrics").isNullOrBlank() }
                bestAny?.let { parseLrclibObject(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrclibObject(obj: JsonObject): LyricsResult {
        val syncedRaw = obj.str("syncedLyrics").orEmpty()
        val plainRaw = obj.str("plainLyrics").orEmpty().trim()
        val lines = parseLrc(syncedRaw)
        return LyricsResult(synced = lines, plain = plainRaw, source = "LRCLIB")
    }

    private fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val lineRe = Regex("^\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]\\s*(.*)$")
        val out = mutableListOf<LyricLine>()
        for (line in raw.lines()) {
            val m = lineRe.matchEntire(line.trim()) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val fracStr = m.groupValues[3]
            val ms = when (fracStr.length) {
                0 -> 0L
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                else -> (fracStr.take(3).toLongOrNull() ?: 0L)
            }
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) {
                out.add(LyricLine(timeMs = (min * 60L + sec) * 1000L + ms, text = text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun readBounded(resp: okhttp3.Response, maxBytes: Long = MAX_BYTES): String? {
        val src = resp.body?.source() ?: return null
        src.request(maxBytes + 1)
        if (src.buffer.size > maxBytes) return null
        return src.buffer.readUtf8()
    }

    private fun putCache(id: String, res: LyricsResult) {
        if (cache.size > 60) cache.clear()
        cache[id] = res
    }

    private fun putTranslationCache(id: String, res: LyricsTranslationResult) {
        if (translationCache.size > 40) translationCache.clear()
        translationCache[id] = res
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024L
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
