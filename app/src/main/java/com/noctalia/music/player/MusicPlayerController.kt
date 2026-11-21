package com.noctalia.music.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.noctalia.music.data.LibraryRepository
import com.noctalia.music.data.LyricsClient
import com.noctalia.music.data.LyricsResult
import com.noctalia.music.data.OfflineCacheManager
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.SkipSegment
import com.noctalia.music.data.SponsorBlockClient
import com.noctalia.music.data.Track
import com.noctalia.music.data.YTMusicApi
import com.noctalia.music.widget.BrookWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class RepeatMode { NONE, PLAYLIST, TRACK }

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val qualityLabel: String = "OPUS 160k",
    val queue: List<Track> = emptyList(),
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val autoRadio: Boolean = true,
    val lyrics: LyricsResult = LyricsResult(),
    val loadingLyrics: Boolean = false,
    val sleepRemainingSec: Int? = null,
    val sleepEndOfTrack: Boolean = false,
    val sponsorSkippedNotice: String? = null,
    val errorMsg: String? = null,
)

class MusicPlayerController(
    private val context: Context,
    private val api: YTMusicApi,
    private val library: LibraryRepository,
    private val lyricsClient: LyricsClient,
    private val sponsorBlock: SponsorBlockClient,
    private val offlineCache: OfflineCacheManager,
    private val audioEffects: AudioEffectsController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        PlayerUiState(autoRadio = library.state.value.settings.autoRadio)
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var mediaController: MediaController? = null
    private var playJob: Job? = null
    private var radioJob: Job? = null
    private var lyricsJob: Job? = null
    private var sponsorJob: Job? = null
    private var sleepJob: Job? = null
    private val history = ArrayDeque<Track>()
    private var retriedCurrent = false

    private var activeSkipSegments: List<SkipSegment> = emptyList()
    private val skippedSegmentKeys = HashSet<String>()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            BrookWidgetProvider.updateAllWidgets(context)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val ctrl = mediaController ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                Player.STATE_READY -> {
                    val dur = ctrl.duration.takeIf { it > 0 } ?: (_state.value.currentTrack?.duration?.times(1000L) ?: 0L)
                    _state.update { it.copy(isLoading = false, durationMs = dur, errorMsg = null) }
                    BrookWidgetProvider.updateAllWidgets(context)
                }
                Player.STATE_ENDED -> {
                    if (_state.value.sleepEndOfTrack) {
                        _state.update { it.copy(sleepEndOfTrack = false, isPlaying = false) }
                        ctrl.pause()
                        BrookWidgetProvider.updateAllWidgets(context)
                        return
                    }
                    if (_state.value.repeatMode == RepeatMode.TRACK) {
                        ctrl.seekTo(0)
                        ctrl.play()
                    } else {
                        skipNext(autoAdvance = true)
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cur = _state.value.currentTrack
            if (cur != null && !retriedCurrent) {
                retriedCurrent = true
                playTrack(cur, pushHistory = false, forceFreshUrl = true)
            } else {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMsg = "Error de reproducción. Pulsa de nuevo para reintentar.",
                    )
                }
                BrookWidgetProvider.updateAllWidgets(context)
            }
        }
    }

    init {
        connectController()
        startPositionTicker()
    }

    private fun connectController() {
        scope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, NoctaliaPlaybackService::class.java))
                val ctrl = MediaController.Builder(context, token).buildAsync().await()
                mediaController = ctrl
                ctrl.addListener(playerListener)
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun ensureController(): MediaController? {
        mediaController?.let { return it }
        return try {
            val token = SessionToken(context, ComponentName(context, NoctaliaPlaybackService::class.java))
            val ctrl = MediaController.Builder(context, token).buildAsync().await()
            mediaController = ctrl
            ctrl.addListener(playerListener)
            ctrl
        } catch (_: Exception) {
            null
        }
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                val ctrl = mediaController
                if (ctrl != null && _state.value.currentTrack != null) {
                    val pos = ctrl.currentPosition.coerceAtLeast(0L)
                    val dur = ctrl.duration.takeIf { it > 0 }
                        ?: (_state.value.currentTrack?.duration?.times(1000L) ?: 0L)

                    // SponsorBlock: saltar intros/segmentos no musicales automáticamente
                    if (ctrl.isPlaying && audioEffects.state.value.sponsorBlockEnabled && activeSkipSegments.isNotEmpty()) {
                        val hit = activeSkipSegments.firstOrNull { seg ->
                            val key = "${seg.startMs}_${seg.endMs}"
                            key !in skippedSegmentKeys && pos >= seg.startMs && pos < (seg.endMs - 800L)
                        }
                        if (hit != null) {
                            skippedSegmentKeys.add("${hit.startMs}_${hit.endMs}")
                            val target = hit.endMs.coerceAtMost((dur - 500L).coerceAtLeast(0L))
                            ctrl.seekTo(target)
                            _state.update { it.copy(sponsorSkippedNotice = "Saltado segmento no musical (SponsorBlock)") }
                            scope.launch {
                                delay(2600L)
                                _state.update { it.copy(sponsorSkippedNotice = null) }
                            }
                        }
                    }

                    _state.update {
                        if (it.positionMs != pos || it.durationMs != dur) {
                            it.copy(positionMs = pos, durationMs = dur)
                        } else {
                            it
                        }
                    }
                }
                delay(280L)
            }
        }
    }

    fun playTrack(track: Track, pushHistory: Boolean = true, forceFreshUrl: Boolean = false) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val prev = _state.value.currentTrack
        if (pushHistory && prev != null && prev.id != clean.id) {
            history.addLast(prev)
            while (history.size > 50) history.removeFirst()
        }

        library.recordRecent(clean)
        if (!forceFreshUrl) retriedCurrent = false
        activeSkipSegments = emptyList()
        skippedSegmentKeys.clear()

        _state.update {
            it.copy(
                currentTrack = clean,
                isLoading = true,
                positionMs = 0L,
                durationMs = clean.duration * 1000L,
                lyrics = LyricsResult(),
                loadingLyrics = true,
                sponsorSkippedNotice = null,
                errorMsg = null,
            )
        }
        BrookWidgetProvider.updateAllWidgets(context)

        // Cargar letras sincronizadas en paralelo
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            val res = lyricsClient.fetchLyrics(clean)
            if (_state.value.currentTrack?.id == clean.id) {
                _state.update { it.copy(lyrics = res, loadingLyrics = false) }
            }
        }

        // Cargar segmentos SponsorBlock en paralelo
        sponsorJob?.cancel()
        if (audioEffects.state.value.sponsorBlockEnabled) {
            sponsorJob = scope.launch {
                val segs = sponsorBlock.fetchSkipSegments(clean.id)
                if (_state.value.currentTrack?.id == clean.id) {
                    activeSkipSegments = segs
                }
            }
        }

        if (_state.value.autoRadio && _state.value.queue.isEmpty()) {
            prefetchRadio(clean.id)
        }

        playJob?.cancel()
        playJob = scope.launch {
            // 1. Comprobar primero si está guardada en caché offline (0 datos, instantáneo)
            val localFile = if (!forceFreshUrl) offlineCache.getLocalFile(clean.id) else null
            val mediaUri: Uri
            val qualityLabel: String

            if (localFile != null) {
                mediaUri = Uri.fromFile(localFile)
                qualityLabel = "OFFLINE · OPUS"
            } else {
                val resolved = api.resolveStreamUrl(clean.id, forceRefresh = forceFreshUrl)
                if (resolved == null) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMsg = "No se pudo cargar el audio de esta canción.",
                        )
                    }
                    BrookWidgetProvider.updateAllWidgets(context)
                    return@launch
                }
                mediaUri = Uri.parse(resolved.url)
                qualityLabel = resolved.qualityLabel
            }

            val ctrl = ensureController()
            if (ctrl == null) {
                _state.update { it.copy(isLoading = false, errorMsg = "Servicio de audio no disponible.") }
                return@launch
            }

            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(clean.title)
                .setArtist(clean.artist)
                .setAlbumTitle(clean.album.ifEmpty { "Brook" })
            if (clean.thumb.isNotBlank()) {
                metadataBuilder.setArtworkUri(Uri.parse(clean.thumb))
            }

            val item = MediaItem.Builder()
                .setMediaId(clean.id)
                .setUri(mediaUri)
                .setMediaMetadata(metadataBuilder.build())
                .build()

            _state.update { it.copy(qualityLabel = qualityLabel) }
            ctrl.volume = 1f
            ctrl.setMediaItem(item)
            ctrl.prepare()
            ctrl.play()
        }
    }

    fun playTrackList(tracks: List<Track>, startIndex: Int = 0) {
        val clean = tracks.mapNotNull { SecurityValidators.sanitizeTrack(it) }
        if (clean.isEmpty()) return
        val idx = startIndex.coerceIn(0, clean.lastIndex)
        val selected = clean[idx]
        val rest = clean.drop(idx + 1)
        _state.update { it.copy(queue = rest) }
        playTrack(selected, pushHistory = true)
    }

    fun togglePlayPause() {
        val ctrl = mediaController ?: return
        if (_state.value.currentTrack == null) {
            val rec = library.state.value.recent.firstOrNull()
            if (rec != null) playTrack(rec, pushHistory = false)
            return
        }
        if (ctrl.isPlaying) {
            ctrl.pause()
        } else {
            ctrl.volume = 1f
            if (ctrl.playbackState == Player.STATE_IDLE || ctrl.playbackState == Player.STATE_ENDED) {
                _state.value.currentTrack?.let { playTrack(it, pushHistory = false) }
            } else {
                ctrl.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val ctrl = mediaController ?: return
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(1L))
        ctrl.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun skipNext(autoAdvance: Boolean = false) {
        val curQueue = _state.value.queue
        if (curQueue.isNotEmpty()) {
            val idx = if (_state.value.shuffle) Random.nextInt(curQueue.size) else 0
            val next = curQueue[idx]
            val remaining = curQueue.toMutableList().apply { removeAt(idx) }
            _state.update { it.copy(queue = remaining) }
            playTrack(next, pushHistory = true)
            return
        }

        if (_state.value.repeatMode == RepeatMode.PLAYLIST && history.isNotEmpty()) {
            val first = history.removeFirst()
            playTrack(first, pushHistory = true)
            return
        }

        val cur = _state.value.currentTrack
        if (_state.value.autoRadio && cur != null) {
            prefetchRadio(cur.id, playImmediately = true)
            return
        }

        if (autoAdvance) {
            mediaController?.pause()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun skipPrevious() {
        val ctrl = mediaController
        if (ctrl != null && ctrl.currentPosition > 4000L) {
            seekTo(0L)
            return
        }
        if (history.isNotEmpty()) {
            val prev = history.removeLast()
            val cur = _state.value.currentTrack
            if (cur != null) {
                _state.update { it.copy(queue = listOf(cur) + it.queue) }
            }
            playTrack(prev, pushHistory = false)
        } else {
            seekTo(0L)
        }
    }

    // ─── TEMPORIZADOR DE APAGADO (SLEEP TIMER CON FADE-OUT SUAVE) ───
    fun setSleepTimerMinutes(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        var rem = minutes * 60
        _state.update { it.copy(sleepRemainingSec = rem, sleepEndOfTrack = false) }
        sleepJob = scope.launch {
            while (rem > 0) {
                delay(1000L)
                rem -= 1
                _state.update { it.copy(sleepRemainingSec = rem) }
            }
            // Fade-out suave de 2 segundos antes de pausar
            val ctrl = mediaController
            if (ctrl != null && ctrl.isPlaying) {
                for (step in 9 downTo 1) {
                    ctrl.volume = step / 10f
                    delay(200L)
                }
                ctrl.pause()
                ctrl.volume = 1f
            }
            _state.update { it.copy(sleepRemainingSec = null, sleepEndOfTrack = false) }
        }
    }

    fun setSleepAtEndOfTrack() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepRemainingSec = null, sleepEndOfTrack = true) }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepRemainingSec = null, sleepEndOfTrack = false) }
    }

    fun addToQueue(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        _state.update { it.copy(queue = it.queue + clean) }
    }

    fun playFromQueue(index: Int) {
        val curQueue = _state.value.queue
        if (index !in curQueue.indices) return
        val chosen = curQueue[index]
        val rest = curQueue.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(queue = rest) }
        playTrack(chosen, pushHistory = true)
    }

    fun removeFromQueue(index: Int) {
        val curQueue = _state.value.queue
        if (index !in curQueue.indices) return
        val rest = curQueue.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(queue = rest) }
    }

    fun clearQueue() {
        _state.update { it.copy(queue = emptyList()) }
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
    }

    fun cycleRepeat() {
        _state.update {
            val nxt = when (it.repeatMode) {
                RepeatMode.NONE -> RepeatMode.PLAYLIST
                RepeatMode.PLAYLIST -> RepeatMode.TRACK
                RepeatMode.TRACK -> RepeatMode.NONE
            }
            it.copy(repeatMode = nxt)
        }
    }

    fun setAutoRadio(enabled: Boolean) {
        library.updateAutoRadio(enabled)
        _state.update { it.copy(autoRadio = enabled) }
        if (enabled && _state.value.queue.isEmpty()) {
            _state.value.currentTrack?.let { prefetchRadio(it.id) }
        }
    }

    private fun prefetchRadio(seedVideoId: String, playImmediately: Boolean = false) {
        if (radioJob?.isActive == true && !playImmediately) return
        radioJob = scope.launch {
            if (playImmediately) _state.update { it.copy(isLoading = true) }
            val radioTracks = api.getRadio(seedVideoId, limit = 20)
            val existingIds = (_state.value.queue.map { it.id } + listOfNotNull(_state.value.currentTrack?.id)).toSet()
            val fresh = radioTracks.filterNot { it.id in existingIds }.take(15)
            if (fresh.isNotEmpty()) {
                if (playImmediately) {
                    _state.update { it.copy(queue = it.queue + fresh.drop(1)) }
                    playTrack(fresh.first(), pushHistory = true)
                } else {
                    _state.update { it.copy(queue = it.queue + fresh) }
                }
            } else if (playImmediately) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
