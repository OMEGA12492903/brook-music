package com.noctalia.music.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Repositorio local de Favoritas, Playlists, Recientes, Stats y Última Sesión.
 * Usa exactamente el mismo esquema JSON que Noctalia Music en CachyOS (`music_library.json`).
 * Escritura atómica en segundo plano con cola secuencial (0 bloqueos en el hilo UI, 0 carreras en .tmp).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(context: Context) {

    private val file = File(context.filesDir, "music_library.json")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<LibraryPayload> = _state.asStateFlow()

    private fun loadFromDisk(): LibraryPayload {
        if (!file.exists()) return LibraryPayload()
        return try {
            val parsed = json.decodeFromString<LibraryPayload>(file.readText(Charsets.UTF_8))
            sanitizePayload(parsed)
        } catch (_: Exception) {
            LibraryPayload()
        }
    }

    private fun sanitizePayload(raw: LibraryPayload): LibraryPayload {
        val favs = raw.favorites
            .mapNotNull { SecurityValidators.sanitizeTrack(it) }
            .distinctBy { it.id }
            .take(MAX_FAVORITES)
        val pls = LinkedHashMap<String, List<Track>>()
        raw.playlists.entries.take(MAX_PLAYLISTS).forEach { (k, v) ->
            val name = k.trim().take(36)
            if (name.isNotEmpty()) {
                pls[name] = v
                    .mapNotNull { SecurityValidators.sanitizeTrack(it) }
                    .distinctBy { it.id }
                    .take(MAX_TRACKS_PER_PLAYLIST)
            }
        }
        val rec = raw.recent
            .mapNotNull { SecurityValidators.sanitizeTrack(it) }
            .distinctBy { it.id }
            .take(30)
        val validBgStyle = if (raw.settings.bgStyle in ALLOWED_BG_STYLES) raw.settings.bgStyle else "liquid"
        val validLoop = if (raw.settings.loopStatus in ALLOWED_LOOP_STATUS) raw.settings.loopStatus else "None"
        val cleanHistory = raw.searchHistory
            .map { it.replace(Regex("[\\r\\n\\u0000-\\u001F]"), "").trim().take(120) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_SEARCH_HISTORY)
        val cleanStats = LinkedHashMap<String, TrackStat>()
        raw.stats.entries.take(MAX_STATS).forEach { (_, st) ->
            val t = SecurityValidators.sanitizeTrack(st.track) ?: return@forEach
            cleanStats[t.id] = TrackStat(
                track = t,
                playCount = st.playCount.coerceIn(0, 100_000),
                listenedSec = st.listenedSec.coerceIn(0L, 10_000_000L),
                lastPlayedMs = st.lastPlayedMs.coerceAtLeast(0L),
            )
        }
        val cleanSnap = raw.lastSession?.let { snap ->
            val stTrack = snap.track?.let { SecurityValidators.sanitizeTrack(it) }
            val stQueue = snap.queue.mapNotNull { SecurityValidators.sanitizeTrack(it) }.take(60)
            if (stTrack != null) {
                PlaybackSnapshot(
                    track = stTrack,
                    positionMs = snap.positionMs.coerceIn(0L, 24L * 3600L * 1000L),
                    queue = stQueue,
                )
            } else {
                null
            }
        }
        return LibraryPayload(
            favorites = favs,
            playlists = if (pls.isEmpty()) mapOf("Gaming" to emptyList(), "Chill" to emptyList()) else pls,
            recent = rec,
            settings = raw.settings.copy(bgStyle = validBgStyle, loopStatus = validLoop),
            searchHistory = cleanHistory,
            stats = cleanStats,
            lastSession = cleanSnap,
        )
    }

    private fun persist(updated: LibraryPayload) {
        _state.value = updated
        ioScope.launch {
            val tmp = File(file.parentFile, "music_library.tmp")
            try {
                tmp.writeText(json.encodeToString(updated), Charsets.UTF_8)
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                try {
                    if (tmp.exists()) {
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (_: Exception) {
                    tmp.delete()
                }
            }
        }
    }

    companion object {
        private const val MAX_FAVORITES = 2000
        private const val MAX_PLAYLISTS = 60
        private const val MAX_TRACKS_PER_PLAYLIST = 1000
        private const val MAX_SEARCH_HISTORY = 12
        private const val MAX_STATS = 500
        private val ALLOWED_BG_STYLES = setOf("liquid", "vortex", "aurora", "particles")
        private val ALLOWED_LOOP_STATUS = setOf("None", "Track", "Playlist")
    }

    fun isFavorite(videoId: String): Boolean = _state.value.favorites.any { it.id == videoId }

    fun toggleFavorite(track: Track): Boolean {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return false
        val cur = _state.value
        val exists = cur.favorites.any { it.id == clean.id }
        val nextFavs = if (exists) {
            cur.favorites.filterNot { it.id == clean.id }
        } else {
            listOf(clean) + cur.favorites
        }
        persist(cur.copy(favorites = nextFavs))
        return !exists
    }

    fun recordRecent(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val cur = _state.value
        val nextRec = (listOf(clean) + cur.recent.filterNot { it.id == clean.id }).take(30)
        persist(cur.copy(recent = nextRec))
    }

    fun createPlaylist(name: String): Boolean {
        val clean = name.trim().take(36)
        if (clean.isEmpty()) return false
        val cur = _state.value
        if (cur.playlists.containsKey(clean)) return false
        val next = LinkedHashMap(cur.playlists)
        next[clean] = emptyList()
        persist(cur.copy(playlists = next))
        return true
    }

    fun savePlaylistWithTracks(name: String, tracks: List<Track>): Int {
        val cleanName = name.trim().take(36).ifEmpty { "Importada" }
        val cleanTracks = tracks.mapNotNull { SecurityValidators.sanitizeTrack(it) }.distinctBy { it.id }
        val cur = _state.value
        val next = LinkedHashMap(cur.playlists)
        val merged = ((next[cleanName] ?: emptyList()) + cleanTracks).distinctBy { it.id }
        next[cleanName] = merged
        persist(cur.copy(playlists = next))
        return cleanTracks.size
    }

    fun mergeFavorites(tracks: List<Track>): Int {
        val cleanTracks = tracks.mapNotNull { SecurityValidators.sanitizeTrack(it) }.distinctBy { it.id }
        val cur = _state.value
        val merged = (cleanTracks + cur.favorites).distinctBy { it.id }
        persist(cur.copy(favorites = merged))
        return cleanTracks.size
    }

    fun deletePlaylist(name: String) {
        val cur = _state.value
        if (!cur.playlists.containsKey(name)) return
        val next = LinkedHashMap(cur.playlists)
        next.remove(name)
        persist(cur.copy(playlists = next))
    }

    fun addTrackToPlaylist(playlistName: String, track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val cur = _state.value
        val existing = cur.playlists[playlistName] ?: return
        if (existing.any { it.id == clean.id }) return
        val next = LinkedHashMap(cur.playlists)
        next[playlistName] = existing + clean
        persist(cur.copy(playlists = next))
    }

    fun removeTrackFromPlaylist(playlistName: String, videoId: String) {
        val cur = _state.value
        val existing = cur.playlists[playlistName] ?: return
        val next = LinkedHashMap(cur.playlists)
        next[playlistName] = existing.filterNot { it.id == videoId }
        persist(cur.copy(playlists = next))
    }

    fun updateAutoRadio(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(autoRadio = enabled)))
    }

    fun updatePlayerBgStyle(styleId: String) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(bgStyle = styleId)))
    }

    fun updatePlayerBgUseCoverColors(useCover: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(bgUseCoverColors = useCover)))
    }

    fun updateDynamicAppTheme(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(dynamicAppTheme = enabled)))
    }

    fun updateSkipSilence(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(skipSilence = enabled)))
    }

    fun updateSmoothFade(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(smoothFade = enabled)))
    }

    // ─── HISTORIAL DE BÚSQUEDAS ───
    fun recordSearchQuery(raw: String) {
        val clean = raw.replace(Regex("[\\r\\n\\u0000-\\u001F]"), "").trim().take(120)
        if (clean.length < 2) return
        val cur = _state.value
        val next = (listOf(clean) + cur.searchHistory.filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_SEARCH_HISTORY)
        persist(cur.copy(searchHistory = next))
    }

    fun removeSearchQuery(query: String) {
        val cur = _state.value
        persist(cur.copy(searchHistory = cur.searchHistory.filterNot { it.equals(query, ignoreCase = true) }))
    }

    fun clearSearchHistory() {
        val cur = _state.value
        if (cur.searchHistory.isEmpty()) return
        persist(cur.copy(searchHistory = emptyList()))
    }

    // ─── ESTADÍSTICAS LOCALES (BROOK STATS) ───
    fun recordTrackPlayStat(track: Track, incrementPlayCount: Boolean, addSeconds: Long) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val cur = _state.value
        val nextStats = LinkedHashMap(cur.stats)
        val existing = nextStats[clean.id]
        val updated = TrackStat(
            track = clean,
            playCount = ((existing?.playCount ?: 0) + if (incrementPlayCount) 1 else 0).coerceAtMost(100_000),
            listenedSec = ((existing?.listenedSec ?: 0L) + addSeconds.coerceIn(0L, 600L)).coerceAtMost(10_000_000L),
            lastPlayedMs = System.currentTimeMillis(),
        )
        nextStats[clean.id] = updated
        // Mantener tope de 500 pistas ordenando por uso si se supera
        val bounded = if (nextStats.size > MAX_STATS) {
            nextStats.entries
                .sortedByDescending { it.value.listenedSec * 2 + it.value.playCount * 30L }
                .take(MAX_STATS)
                .associateTo(LinkedHashMap()) { it.key to it.value }
        } else {
            nextStats
        }
        persist(cur.copy(stats = bounded))
    }

    fun clearStats() {
        val cur = _state.value
        if (cur.stats.isEmpty()) return
        persist(cur.copy(stats = emptyMap()))
    }

    // ─── SNAPSHOT DE ÚLTIMA SESIÓN (RECORDAR CANCIÓN, SEGUNDO Y COLA) ───
    fun savePlaybackSnapshot(track: Track?, positionMs: Long, queue: List<Track>) {
        val clean = track?.let { SecurityValidators.sanitizeTrack(it) } ?: return
        val cleanQueue = queue.mapNotNull { SecurityValidators.sanitizeTrack(it) }.take(60)
        val snap = PlaybackSnapshot(
            track = clean,
            positionMs = positionMs.coerceIn(0L, 24L * 3600L * 1000L),
            queue = cleanQueue,
        )
        val cur = _state.value
        if (cur.lastSession == snap) return
        persist(cur.copy(lastSession = snap))
    }

    fun exportJson(): String = json.encodeToString(_state.value)

    fun importJson(rawJson: String): Boolean {
        return try {
            val parsed = json.decodeFromString<LibraryPayload>(rawJson)
            val safe = sanitizePayload(parsed)
            val cur = _state.value
            val mergedFavs = (safe.favorites + cur.favorites).distinctBy { it.id }
            val mergedPls = LinkedHashMap(cur.playlists)
            safe.playlists.forEach { (k, v) ->
                mergedPls[k] = ((mergedPls[k] ?: emptyList()) + v).distinctBy { it.id }
            }
            val mergedRec = (safe.recent + cur.recent).distinctBy { it.id }.take(30)
            persist(cur.copy(favorites = mergedFavs, playlists = mergedPls, recent = mergedRec))
            true
        } catch (_: Exception) {
            false
        }
    }
}
