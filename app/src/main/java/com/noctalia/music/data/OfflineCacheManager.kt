package com.noctalia.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

data class OfflineState(
    val cachedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val totalBytes: Long = 0L,
)

/**
 * Caché opcional sin conexión (modo avión / metro).
 * Guarda únicamente las canciones que el usuario marca para offline dentro del directorio privado
 * de la app (`filesDir/offline_audio/`), usando `api.streamHttpClient` (0 cookies, tope 30 MiB/pista).
 */
class OfflineCacheManager(
    context: Context,
    private val api: YTMusicApi,
) {
    private val dir = File(context.filesDir, "offline_audio").apply { mkdirs() }

    private val _state = MutableStateFlow(scanDisk(emptySet()))
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    private fun scanDisk(downloading: Set<String>): OfflineState {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") && it.length() > 1024L }.orEmpty()
        val ids = files.map { it.name.removeSuffix(".audio") }.filter { SecurityValidators.isValidVideoId(it) }.toSet()
        val total = files.sumOf { it.length() }
        return OfflineState(cachedIds = ids, downloadingIds = downloading, totalBytes = total)
    }

    fun getLocalFile(videoId: String): File? {
        if (!SecurityValidators.isValidVideoId(videoId)) return null
        val f = File(dir, "$videoId.audio")
        return if (f.exists() && f.length() > 1024L) f else null
    }

    fun isCached(videoId: String): Boolean = getLocalFile(videoId) != null

    suspend fun cacheTrack(track: Track): Boolean = withContext(Dispatchers.IO) {
        val vid = track.id
        if (!SecurityValidators.isValidVideoId(vid)) return@withContext false
        if (isCached(vid)) return@withContext true

        _state.value = _state.value.copy(downloadingIds = _state.value.downloadingIds + vid)
        val target = File(dir, "$vid.audio")
        val tmp = File(dir, "$vid.tmp")
        try {
            val stream = api.resolveStreamUrl(vid) ?: return@withContext false
            if (!SecurityValidators.isSafeStreamUrl(stream.url)) return@withContext false

            val req = Request.Builder()
                .url(stream.url)
                .header("User-Agent", stream.userAgent)
                .get()
                .build()

            api.streamHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                if (body.contentLength() > MAX_TRACK_BYTES) return@withContext false
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16_384)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            copied += n
                            if (copied > MAX_TRACK_BYTES) throw IllegalStateException("Pista demasiado grande")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        } finally {
            val remainingDl = _state.value.downloadingIds - vid
            _state.value = scanDisk(remainingDl)
        }
    }

    fun removeTrack(videoId: String) {
        if (!SecurityValidators.isValidVideoId(videoId)) return
        File(dir, "$videoId.audio").delete()
        _state.value = scanDisk(_state.value.downloadingIds)
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        _state.value = scanDisk(_state.value.downloadingIds)
    }

    companion object {
        private const val MAX_TRACK_BYTES = 30L * 1024L * 1024L
    }
}
