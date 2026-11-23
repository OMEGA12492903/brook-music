package com.noctalia.music.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LocalAudioState(
    val hasPermission: Boolean = false,
    val scanning: Boolean = false,
    val tracks: List<Track> = emptyList(),
)

/**
 * Escáner nativo de música local del dispositivo (MP3, FLAC, M4A, OGG, WAV, OPUS) usando MediaStore.
 * Genera pistas con ID validado `loc_<mediaStoreId>` reproducibles offline sin consumo de red
 * y totalmente compatibles con Favoritas, Playlists, Cola, EQ, Crossfade y Android Auto.
 */
class LocalAudioRepository(private val context: Context) {

    private val _state = MutableStateFlow(LocalAudioState(hasPermission = checkPermission()))
    val state: StateFlow<LocalAudioState> = _state.asStateFlow()

    fun requiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun refresh(): List<Track> = withContext(Dispatchers.IO) {
        val granted = checkPermission()
        if (!granted) {
            _state.value = LocalAudioState(hasPermission = false, scanning = false, tracks = emptyList())
            return@withContext emptyList()
        }
        _state.value = _state.value.copy(hasPermission = true, scanning = true)
        val result = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 5000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val albumArtBase = Uri.parse("content://media/external/audio/albumart")

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext() && result.size < MAX_LOCAL_TRACKS) {
                    val mediaId = cursor.getLong(idCol)
                    if (mediaId <= 0L) continue
                    val rawTitle = cursor.getString(titleCol).orEmpty().trim()
                    if (rawTitle.isEmpty()) continue
                    val rawArtist = cursor.getString(artistCol).orEmpty().trim()
                    val artist = if (rawArtist.isEmpty() || rawArtist.equals("<unknown>", ignoreCase = true)) {
                        "Archivo local"
                    } else {
                        rawArtist
                    }
                    val rawAlbum = cursor.getString(albumCol).orEmpty().trim()
                    val album = if (rawAlbum.equals("<unknown>", ignoreCase = true)) "" else rawAlbum
                    val durSec = (cursor.getLong(durCol) / 1000L).toInt().coerceIn(0, 86_400)
                    val albumId = cursor.getLong(albumIdCol)
                    val artUri = if (albumId > 0L) {
                        ContentUris.withAppendedId(albumArtBase, albumId).toString()
                    } else {
                        ""
                    }
                    val track = SecurityValidators.sanitizeTrack(
                        Track(
                            id = "loc_$mediaId",
                            title = rawTitle,
                            artist = artist,
                            album = album,
                            duration = durSec,
                            durationText = if (durSec > 0) SecurityValidators.fmtDuration(durSec) else "",
                            thumb = artUri,
                        )
                    )
                    if (track != null) result.add(track)
                }
            }
        } catch (_: Exception) {
        }
        _state.value = LocalAudioState(hasPermission = true, scanning = false, tracks = result)
        result
    }

    fun resolveQualityLabel(trackId: String): String {
        val mediaId = SecurityValidators.extractLocalMediaId(trackId) ?: return "LOCAL"
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.MIME_TYPE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val mime = c.getString(0).orEmpty().lowercase()
                        when {
                            mime.contains("flac") -> "LOCAL · FLAC"
                            mime.contains("mpeg") || mime.contains("mp3") -> "LOCAL · MP3"
                            mime.contains("mp4") || mime.contains("aac") || mime.contains("m4a") -> "LOCAL · AAC"
                            mime.contains("ogg") || mime.contains("opus") -> "LOCAL · OPUS"
                            mime.contains("wav") -> "LOCAL · WAV"
                            else -> "LOCAL · AUDIO"
                        }
                    } else {
                        "LOCAL · AUDIO"
                    }
                } ?: "LOCAL · AUDIO"
        } catch (_: Exception) {
            "LOCAL · AUDIO"
        }
    }

    fun findById(trackId: String): Track? = _state.value.tracks.firstOrNull { it.id == trackId }

    companion object {
        private const val MAX_LOCAL_TRACKS = 1500
    }
}
