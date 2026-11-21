package com.noctalia.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Repositorio local de Favoritas, Playlists y Recientes.
 * Usa exactamente el mismo esquema JSON que Noctalia Music en CachyOS (`music_library.json`).
 */
class LibraryRepository(context: Context) {

    private val file = File(context.filesDir, "music_library.json")
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
        return LibraryPayload(
            favorites = favs,
            playlists = if (pls.isEmpty()) mapOf("Gaming" to emptyList(), "Chill" to emptyList()) else pls,
            recent = rec,
            settings = raw.settings,
        )
    }

    private fun persist(updated: LibraryPayload) {
        _state.value = updated
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

    companion object {
        private const val MAX_FAVORITES = 2000
        private const val MAX_PLAYLISTS = 60
        private const val MAX_TRACKS_PER_PLAYLIST = 1000
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
