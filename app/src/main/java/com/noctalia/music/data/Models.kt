package com.noctalia.music.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Int = 0,
    @SerialName("duration_text") val durationText: String = "",
    val thumb: String = "",
)

@Serializable
data class LibrarySettings(
    @SerialName("auto_radio") val autoRadio: Boolean = true,
    @SerialName("auto_pause_on_video") val autoPauseOnVideo: Boolean = true,
    val shuffle: Boolean = false,
    @SerialName("loop_status") val loopStatus: String = "None",
    @SerialName("bg_style") val bgStyle: String = "liquid",
    @SerialName("bg_use_cover_colors") val bgUseCoverColors: Boolean = true,
    @SerialName("dynamic_app_theme") val dynamicAppTheme: Boolean = true,
)

/**
 * Formato 100% compatible con ~/.local/state/noctalia/music_library.json en tu PC CachyOS.
 */
@Serializable
data class LibraryPayload(
    val favorites: List<Track> = emptyList(),
    val playlists: Map<String, List<Track>> = mapOf("Gaming" to emptyList(), "Chill" to emptyList()),
    val recent: List<Track> = emptyList(),
    val settings: LibrarySettings = LibrarySettings(),
)

data class RemotePlaylist(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumb: String,
)

data class ResolvedStream(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val qualityLabel: String,
    val userAgent: String,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)

object SecurityValidators {
    private val VIDEO_ID_RE = Regex("^[A-Za-z0-9_-]{8,16}$")
    private val PLAYLIST_ID_RE = Regex("^[A-Za-z0-9_-]{2,80}$")
    private val THUMB_SIZE_RE = Regex("=w\\d+-h\\d+[^&]*")
    private val DUR_RE = Regex("^\\d+:\\d{2}(?::\\d{2})?$")

    private val ALLOWED_THUMB_SUFFIXES = listOf(".googleusercontent.com", ".ytimg.com", ".ggpht.com")
    private val ALLOWED_STREAM_SUFFIXES = listOf(".googlevideo.com", ".youtube.com")

    fun isValidVideoId(id: String?): Boolean = !id.isNullOrBlank() && VIDEO_ID_RE.matches(id.trim())

    fun isValidBrowseId(id: String?): Boolean = !id.isNullOrBlank() && PLAYLIST_ID_RE.matches(id.trim())

    private fun hostMatches(host: String?, suffixes: List<String>): Boolean {
        val h = host?.trim()?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        return suffixes.any { sfx ->
            val bare = sfx.removePrefix(".")
            h == bare || h.endsWith(sfx)
        }
    }

    fun isSafeThumbUrl(url: String?): Boolean {
        val u = url?.trim() ?: return false
        return try {
            val uri = URI(u)
            uri.scheme.equals("https", ignoreCase = true) && hostMatches(uri.host, ALLOWED_THUMB_SUFFIXES)
        } catch (_: Exception) {
            false
        }
    }

    fun isSafeStreamUrl(url: String?): Boolean {
        val u = url?.trim() ?: return false
        return try {
            val uri = URI(u)
            uri.scheme.equals("https", ignoreCase = true) && hostMatches(uri.host, ALLOWED_STREAM_SUFFIXES)
        } catch (_: Exception) {
            false
        }
    }

    fun upgradeThumbUrl(url: String?): String {
        val u = url?.trim() ?: return ""
        if (!isSafeThumbUrl(u)) return ""
        val upgraded = if (u.contains("googleusercontent.com") || u.contains("ggpht.com")) {
            if (THUMB_SIZE_RE.containsMatchIn(u)) {
                THUMB_SIZE_RE.replace(u, "=w544-h544-l90-rj")
            } else {
                u
            }
        } else {
            u
        }
        return if (isSafeThumbUrl(upgraded)) upgraded else ""
    }

    fun parseDurationSec(text: String?): Int {
        val s = text?.trim() ?: return 0
        if (!DUR_RE.matches(s)) return 0
        val parts = s.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    fun fmtDuration(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return if (m >= 60) {
            val h = m / 60
            val mm = m % 60
            "%d:%02d:%02d".format(h, mm, r)
        } else {
            "%d:%02d".format(m, r)
        }
    }

    fun sanitizeTrack(raw: Track): Track? {
        val vid = raw.id.trim()
        if (!isValidVideoId(vid)) return null
        val dur = if (raw.duration > 0) raw.duration else parseDurationSec(raw.durationText)
        return Track(
            id = vid,
            title = raw.title.trim().ifEmpty { "Sin título" }.take(240),
            artist = raw.artist.trim().ifEmpty { "Artista desconocido" }.take(240),
            album = raw.album.trim().take(240),
            duration = dur,
            durationText = raw.durationText.trim().ifEmpty { if (dur > 0) fmtDuration(dur) else "" }.take(16),
            thumb = upgradeThumbUrl(raw.thumb),
        )
    }

    fun extractPlaylistIdFromUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val listParam = Regex("[?&]list=([A-Za-z0-9_-]{2,80})").find(trimmed)?.groupValues?.getOrNull(1)
        if (listParam != null) return listParam
        if (PLAYLIST_ID_RE.matches(trimmed)) return trimmed
        return null
    }
}
