package com.noctalia.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

/**
 * Cliente aislado y seguro para letras sincronizadas (LRCLIB API pública sin clave).
 * Solo permite https://lrclib.net, sin cookies y respuestas limitadas a 512 KiB.
 */
class LyricsClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, LyricsResult>()

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

        // 1. Consulta exacta en /api/get
        val exact = queryExact(cleanTitle, cleanArtist, track.duration)
        if (exact != null && exact.hasLyrics) {
            putCache(track.id, exact)
            return@withContext exact
        }

        // 2. Búsqueda flexible en /api/search
        val searched = querySearch("$cleanTitle $cleanArtist")
        if (searched != null && searched.hasLyrics) {
            putCache(track.id, searched)
            return@withContext searched
        }

        // No cachear fallos vacíos permanentemente por si el usuario estaba sin red
        LyricsResult()
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
            .header("User-Agent", "Brook-Android/2.0")
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
            .header("User-Agent", "Brook-Android/2.0")
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = readBounded(resp) ?: return null
                val arr = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray ?: return null
                val objs = arr.mapNotNull { it as? JsonObject }
                // Preferir resultado con letra sincronizada
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

    private fun readBounded(resp: okhttp3.Response): String? {
        val src = resp.body?.source() ?: return null
        src.request(MAX_BYTES + 1)
        if (src.buffer.size > MAX_BYTES) return null
        return src.buffer.readUtf8()
    }

    private fun putCache(id: String, res: LyricsResult) {
        if (cache.size > 60) cache.clear()
        cache[id] = res
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024L
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
