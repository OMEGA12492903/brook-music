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
                skipCount = st.skipCount.coerceIn(0, 100_000),
                completeCount = st.completeCount.coerceIn(0, 100_000),
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
        val cleanProfiles = LinkedHashMap<String, DeviceAudioProfile>()
        raw.settings.audioProfiles.entries.take(24).forEach { (k, p) ->
            val key = k.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
            if (key.isNotEmpty()) {
                val safeBands = p.bandLevelsMb.take(10).map { it.coerceIn(-1500, 1500) }
                    .ifEmpty { listOf(0, 0, 0, 0, 0) }
                cleanProfiles[key] = DeviceAudioProfile(
                    presetName = p.presetName.trim().take(24).ifEmpty { "FLAT" },
                    bandLevelsMb = safeBands,
                    bassBoost = p.bassBoost.coerceIn(0, 1000),
                    normalizeVolume = p.normalizeVolume,
                )
            }
        }
        return LibraryPayload(
            favorites = favs,
            playlists = if (pls.isEmpty()) mapOf("Gaming" to emptyList(), "Chill" to emptyList()) else pls,
            recent = rec,
            settings = raw.settings.copy(
                bgStyle = validBgStyle,
                loopStatus = validLoop,
                crossfadeSec = raw.settings.crossfadeSec.coerceIn(0, 10),
                autoCacheMaxMb = raw.settings.autoCacheMaxMb.coerceIn(100, 4096),
                audioProfiles = cleanProfiles,
            ),
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

    fun updateCrossfadeSec(sec: Int) {
        val clamped = sec.coerceIn(0, 10)
        val cur = _state.value
        if (cur.settings.crossfadeSec == clamped) return
        persist(cur.copy(settings = cur.settings.copy(crossfadeSec = clamped)))
    }

    fun updateAutoCacheWifi(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(autoCacheWifi = enabled)))
    }

    fun updateAutoCacheMaxMb(maxMb: Int) {
        val clamped = maxMb.coerceIn(100, 4096)
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(autoCacheMaxMb = clamped)))
    }

    fun updateAutoCacheFavorites(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(autoCacheFavorites = enabled)))
    }

    fun updatePerDeviceAudioProfiles(enabled: Boolean) {
        val cur = _state.value
        persist(cur.copy(settings = cur.settings.copy(perDeviceAudioProfiles = enabled)))
    }

    fun saveDeviceAudioProfile(deviceKey: String, profile: DeviceAudioProfile) {
        val cleanKey = deviceKey.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        if (cleanKey.isEmpty()) return
        val cur = _state.value
        val nextMap = LinkedHashMap(cur.settings.audioProfiles)
        nextMap[cleanKey] = profile.copy(
            bandLevelsMb = profile.bandLevelsMb.take(10).map { it.coerceIn(-1500, 1500) },
            bassBoost = profile.bassBoost.coerceIn(0, 1000),
        )
        while (nextMap.size > 24) {
            val firstKey = nextMap.keys.firstOrNull() ?: break
            if (firstKey == cleanKey) break
            nextMap.remove(firstKey)
        }
        persist(cur.copy(settings = cur.settings.copy(audioProfiles = nextMap)))
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
            skipCount = existing?.skipCount ?: 0,
            completeCount = existing?.completeCount ?: 0,
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

    /**
     * Registra un salto rápido (< 18 s) para que el algoritmo local penalice canciones/estilos no deseados.
     */
    fun recordTrackSkip(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val cur = _state.value
        val nextStats = LinkedHashMap(cur.stats)
        val existing = nextStats[clean.id]
        nextStats[clean.id] = TrackStat(
            track = clean,
            playCount = existing?.playCount ?: 0,
            listenedSec = existing?.listenedSec ?: 0L,
            lastPlayedMs = existing?.lastPlayedMs ?: System.currentTimeMillis(),
            skipCount = ((existing?.skipCount ?: 0) + 1).coerceAtMost(100_000),
            completeCount = existing?.completeCount ?: 0,
        )
        val bounded = if (nextStats.size > MAX_STATS) {
            nextStats.entries
                .sortedByDescending { it.value.listenedSec * 2 + it.value.playCount * 30L + it.value.skipCount * 5L }
                .take(MAX_STATS)
                .associateTo(LinkedHashMap()) { it.key to it.value }
        } else {
            nextStats
        }
        persist(cur.copy(stats = bounded))
    }

    /**
     * Registra una escucha completa (>= 75% o fin natural) para reforzar la afinidad con ese artista/estilo.
     */
    fun recordTrackCompletion(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val cur = _state.value
        val nextStats = LinkedHashMap(cur.stats)
        val existing = nextStats[clean.id]
        nextStats[clean.id] = TrackStat(
            track = clean,
            playCount = maxOf(existing?.playCount ?: 0, 1),
            listenedSec = existing?.listenedSec ?: 0L,
            lastPlayedMs = System.currentTimeMillis(),
            skipCount = existing?.skipCount ?: 0,
            completeCount = ((existing?.completeCount ?: 0) + 1).coerceAtMost(100_000),
        )
        val bounded = if (nextStats.size > MAX_STATS) {
            nextStats.entries
                .sortedByDescending { it.value.listenedSec * 2 + it.value.playCount * 30L + it.value.completeCount * 40L }
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

    fun buildCloudBackupPayload(): CloudBackupPayload {
        val cur = _state.value
        // No subir metadatos/IDs de archivos locales del teléfono (`loc_<mediaStoreId>`): son privados y específicos de este dispositivo.
        val cloudStats = cur.stats.filterValues { !SecurityValidators.isLocalTrackId(it.track.id) }
        val cloudFavs = cur.favorites.filterNot { SecurityValidators.isLocalTrackId(it.id) }
        val cloudPls = cur.playlists
            .mapValues { (_, list) -> list.filterNot { SecurityValidators.isLocalTrackId(it.id) } }
            .filterValues { it.isNotEmpty() }
        return CloudBackupPayload(
            version = 1,
            updatedAtMs = System.currentTimeMillis(),
            stats = cloudStats,
            favorites = cloudFavs,
            playlists = cloudPls,
        )
    }

    /**
     * Mezcla de forma segura y sin pérdidas una copia remota de GitHub con los datos locales:
     * conserva siempre el máximo de reproducciones y segundos por canción, y fusiona favoritas/playlists.
     */
    fun mergeCloudBackup(remote: CloudBackupPayload): Boolean {
        val cur = _state.value

        val mergedStats = LinkedHashMap(cur.stats)
        remote.stats.entries.take(MAX_STATS).forEach { (_, rStat) ->
            val cleanTrack = SecurityValidators.sanitizeTrack(rStat.track) ?: return@forEach
            if (SecurityValidators.isLocalTrackId(cleanTrack.id)) return@forEach
            val existing = mergedStats[cleanTrack.id]
            mergedStats[cleanTrack.id] = TrackStat(
                track = existing?.track ?: cleanTrack,
                playCount = maxOf(existing?.playCount ?: 0, rStat.playCount.coerceIn(0, 100_000)),
                listenedSec = maxOf(existing?.listenedSec ?: 0L, rStat.listenedSec.coerceIn(0L, 10_000_000L)),
                lastPlayedMs = maxOf(existing?.lastPlayedMs ?: 0L, rStat.lastPlayedMs.coerceAtLeast(0L)),
                skipCount = maxOf(existing?.skipCount ?: 0, rStat.skipCount.coerceIn(0, 100_000)),
                completeCount = maxOf(existing?.completeCount ?: 0, rStat.completeCount.coerceIn(0, 100_000)),
            )
        }
        val boundedStats = if (mergedStats.size > MAX_STATS) {
            mergedStats.entries
                .sortedByDescending { it.value.listenedSec * 2 + it.value.playCount * 30L }
                .take(MAX_STATS)
                .associateTo(LinkedHashMap()) { it.key to it.value }
        } else {
            mergedStats
        }

        val cleanRemoteFavs = remote.favorites
            .mapNotNull { SecurityValidators.sanitizeTrack(it) }
            .filterNot { SecurityValidators.isLocalTrackId(it.id) }
        val mergedFavs = (cur.favorites + cleanRemoteFavs).distinctBy { it.id }.take(MAX_FAVORITES)

        val mergedPls = LinkedHashMap(cur.playlists)
        remote.playlists.entries.take(MAX_PLAYLISTS).forEach { (rawName, rawTracks) ->
            val name = rawName.trim().take(36)
            if (name.isEmpty()) return@forEach
            val cleanTracks = rawTracks
                .mapNotNull { SecurityValidators.sanitizeTrack(it) }
                .filterNot { SecurityValidators.isLocalTrackId(it.id) }
            if (cleanTracks.isEmpty()) return@forEach
            val existing = mergedPls[name].orEmpty()
            mergedPls[name] = (existing + cleanTracks).distinctBy { it.id }.take(MAX_TRACKS_PER_PLAYLIST)
        }

        val changed = boundedStats != cur.stats || mergedFavs != cur.favorites || mergedPls != cur.playlists
        if (changed) {
            persist(cur.copy(stats = boundedStats, favorites = mergedFavs, playlists = mergedPls))
        }
        return changed
    }

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
