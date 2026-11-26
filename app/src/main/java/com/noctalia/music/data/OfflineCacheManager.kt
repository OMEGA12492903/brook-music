package com.noctalia.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class OfflineState(
    val cachedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val totalBytes: Long = 0L,
)

/**
 * Caché sin conexión + Auto-caché inteligente en Wi-Fi (con expulsión LRU respetando descargas fijadas).
 * Guarda archivos únicamente dentro del directorio privado (`filesDir/offline_audio/`) usando
 * `api.streamHttpClient` (0 cookies, tope 30 MiB/pista).
 */
class OfflineCacheManager(
    private val context: Context,
    private val api: YTMusicApi,
) {
    private val dir = File(context.filesDir, "offline_audio").apply { mkdirs() }
    private val pinnedFile = File(dir, "pinned_ids.txt")
    private val pinnedIds = ConcurrentHashMap.newKeySet<String>().apply {
        try {
            if (pinnedFile.exists()) {
                pinnedFile.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { SecurityValidators.isValidVideoId(it) }
                    .forEach { add(it) }
            }
        } catch (_: Exception) {
        }
    }
    private val inFlightIds = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(scanDisk(emptySet()))
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    @Synchronized
    private fun savePinned() {
        val tmp = File(dir, "pinned_ids.tmp")
        try {
            tmp.writeText(pinnedIds.joinToString("\n"), Charsets.UTF_8)
            if (!tmp.renameTo(pinnedFile)) {
                tmp.copyTo(pinnedFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun scanDisk(downloading: Set<String>): OfflineState {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") && it.length() > 1024L }.orEmpty()
        val ids = files.map { it.name.removeSuffix(".audio") }.filter { SecurityValidators.isValidVideoId(it) }.toSet()
        val total = files.sumOf { it.length() }
        return OfflineState(cachedIds = ids, downloadingIds = downloading, totalBytes = total)
    }

    fun isOnUnmeteredWifi(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: Exception) {
            false
        }
    }

    fun getLocalFile(videoId: String): File? {
        if (!SecurityValidators.isValidVideoId(videoId)) return null
        val f = File(dir, "$videoId.audio")
        if (f.exists() && f.length() > 1024L) {
            // Actualizar marca de uso para expulsión LRU precisa
            try { f.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
            return f
        }
        return null
    }

    fun isCached(videoId: String): Boolean {
        if (!SecurityValidators.isValidVideoId(videoId)) return false
        val f = File(dir, "$videoId.audio")
        return f.exists() && f.length() > 1024L
    }

    suspend fun cacheTrack(track: Track, pin: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val vid = track.id
        if (!SecurityValidators.isValidVideoId(vid)) return@withContext false
        if (isCached(vid)) {
            if (pin && pinnedIds.add(vid)) savePinned()
            return@withContext true
        }
        if (!inFlightIds.add(vid)) return@withContext false

        _state.value = scanDisk(inFlightIds.toSet())
        val target = File(dir, "$vid.audio")
        val tmp = File(dir, "$vid.tmp")
        try {
            val stream = api.resolveStreamUrl(vid, preferHls = false) ?: return@withContext false
            if (!SecurityValidators.isSafeStreamUrl(stream.url)) return@withContext false
            if (!stream.mimeType.startsWith("audio/", ignoreCase = true)) return@withContext false

            val req = Request.Builder()
                .url(stream.url)
                .header("User-Agent", stream.userAgent)
                .header("Range", "bytes=0-")
                .get()
                .build()

            var copied = 0L
            api.streamHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                if (body.contentLength() > MAX_TRACK_BYTES) return@withContext false
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16_384)
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
            if (copied <= 1024L) {
                tmp.delete()
                return@withContext false
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            if (pin && pinnedIds.add(vid)) savePinned()
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        } finally {
            inFlightIds.remove(vid)
            _state.value = scanDisk(inFlightIds.toSet())
        }
    }

    /**
     * Auto-caché inteligente en Wi-Fi: descarga silenciosamente la canción escuchada y expulsa
     * por LRU las pistas auto-cacheadas más antiguas si se supera `maxMb`, sin borrar jamás
     * las canciones fijadas manualmente ni las favoritas protegidas.
     */
    suspend fun autoCacheIfWifi(
        track: Track,
        maxMb: Int,
        protectedIds: Set<String> = emptySet(),
        pin: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!SecurityValidators.isValidVideoId(track.id)) return@withContext false
        if (!isOnUnmeteredWifi()) return@withContext false
        val maxBytes = maxMb.coerceIn(100, 4096).toLong() * 1024L * 1024L
        if (!isCached(track.id)) {
            val ok = cacheTrack(track, pin = pin)
            if (!ok) return@withContext false
        } else if (pin && pinnedIds.add(track.id)) {
            savePinned()
        }
        evictAutoCacheIfNeeded(maxBytes, protectedIds + track.id)
        true
    }

    fun evictAutoCacheIfNeeded(maxBytes: Long, protectedIds: Set<String> = emptySet()) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".audio") }
            .orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        val evictable = files
            .filter { f ->
                val id = f.name.removeSuffix(".audio")
                id !in pinnedIds && id !in protectedIds
            }
            .sortedBy { it.lastModified() }

        for (f in evictable) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
            }
        }
        _state.value = scanDisk(inFlightIds.toSet())
    }

    fun removeTrack(videoId: String) {
        if (!SecurityValidators.isValidVideoId(videoId)) return
        File(dir, "$videoId.audio").delete()
        if (pinnedIds.remove(videoId)) savePinned()
        _state.value = scanDisk(inFlightIds.toSet())
    }

    @Synchronized
    fun clearAll() {
        val activeTmpNames = inFlightIds.map { "$it.tmp" }.toSet()
        dir.listFiles()?.forEach { f ->
            if (f.name !in activeTmpNames) {
                f.delete()
            }
        }
        pinnedIds.clear()
        _state.value = scanDisk(inFlightIds.toSet())
    }

    companion object {
        private const val MAX_TRACK_BYTES = 30L * 1024L * 1024L
    }
}
