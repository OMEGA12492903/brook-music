package com.noctalia.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
)

/**
 * Cliente aislado para SponsorBlock: detecta intros no musicales, patrocinadores y silencios
 * en videoclips de YouTube para saltarlos automáticamente.
 * Solo permite https://sponsor.ajay.app, sin cookies y límite 256 KiB.
 */
class SponsorBlockClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, List<SkipSegment>>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.url.scheme != "https" || req.url.host != "sponsor.ajay.app") {
                throw IOException("Blocked non-SponsorBlock host: ${req.url.host}")
            }
            chain.proceed(req.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
        })
        .build()

    suspend fun fetchSkipSegments(videoId: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (!SecurityValidators.isValidVideoId(videoId)) return@withContext emptyList()
        cache[videoId]?.let { return@withContext it }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("sponsor.ajay.app")
            .addPathSegments("api/skipSegments")
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("category", "music_offtopic")
            .addQueryParameter("category", "sponsor")
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Brook-Android/2.0")
            .get()
            .build()

        var shouldCache = false
        val parsed = try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    shouldCache = true
                    return@use emptyList()
                }
                if (!resp.isSuccessful) return@use emptyList()
                shouldCache = true
                val src = resp.body?.source() ?: return@use emptyList()
                src.request(MAX_BYTES + 1)
                if (src.buffer.size > MAX_BYTES) return@use emptyList()
                val arr = json.parseToJsonElement(src.buffer.readUtf8()) as? JsonArray ?: return@use emptyList()
                arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val seg = o["segment"] as? JsonArray ?: return@mapNotNull null
                    val startS = (seg.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val endS = (seg.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val cat = (o["category"] as? JsonPrimitive)?.contentOrNull ?: "skip"
                    if (endS - startS < 1.5) return@mapNotNull null
                    SkipSegment((startS * 1000.0).toLong(), (endS * 1000.0).toLong(), cat)
                }.sortedBy { it.startMs }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (shouldCache) {
            if (cache.size > 80) cache.clear()
            cache[videoId] = parsed
        }
        parsed
    }

    companion object {
        private const val MAX_BYTES = 256L * 1024L
    }
}
