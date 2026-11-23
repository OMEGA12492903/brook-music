package com.noctalia.music.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
        run {
            val lib = library.state.value
            val snap = lib.lastSession
            PlayerUiState(
                currentTrack = snap?.track,
                positionMs = snap?.positionMs ?: 0L,
                durationMs = (snap?.track?.duration ?: 0) * 1000L,
                queue = snap?.queue.orEmpty(),
                autoRadio = lib.settings.autoRadio,
            )
        }
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    // Referencia directa al ExoPlayer dentro de NoctaliaPlaybackService (mismo proceso, 0 latencia IPC)
    private var servicePlayer: ExoPlayer? = null
    private var mediaController: MediaController? = null

    private var playJob: Job? = null
    private var radioJob: Job? = null
    private var radioSeedVideoId: String? = null
    private var pendingPlayRadioWhenReady = false
    private var lyricsJob: Job? = null
    private var sponsorJob: Job? = null
    private var sleepJob: Job? = null
    private var prefetchJob: Job? = null
    private var fadeJob: Job? = null

    private val history = ArrayDeque<Track>()
    private val failedPreloadIds = HashSet<String>()
    private var retriedCurrent = false
    private var consecutiveAutoErrors = 0
    private var isFadingOutToPause = false
    private var prefetchedTrackId: String? = null
    private var plannedNextTrackId: String? = null
    private var countedPlayForTrackId: String? = null
    private var listeningAccumMs: Long = 0L
    private var lastSnapshotSavedMs: Long = 0L

    @Volatile
    var isTransitioningNext: Boolean = false
        private set

    private val transitionWakeLock: PowerManager.WakeLock? by lazy {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brook:track_transition")?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private var activeSkipSegments: List<SkipSegment> = emptyList()
    private val skippedSegmentKeys = HashSet<String>()

    private fun activePlayer(): Player? = servicePlayer ?: mediaController?.takeIf { it.isConnected }

    private fun acquireTransitionWakeLock(timeoutMs: Long = 25_000L) {
        try {
            transitionWakeLock?.acquire(timeoutMs)
        } catch (_: Throwable) {
        }
    }

    private fun releaseTransitionWakeLock() {
        try {
            val wl = transitionWakeLock
            if (wl != null && wl.isHeld) {
                wl.release()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Llamado por `NoctaliaPlaybackService.onCreate()` para enlazar directamente el ExoPlayer real.
     * Evita depender únicamente de Binder IPC en segundo plano o tras cerrar la Activity.
     */
    fun attachServicePlayer(player: ExoPlayer) {
        if (servicePlayer === player) return
        try {
            servicePlayer?.removeListener(playerListener)
        } catch (_: Throwable) {
        }
        try {
            mediaController?.removeListener(playerListener)
        } catch (_: Throwable) {
        }
        servicePlayer = player
        player.addListener(playerListener)
        applySpeedPitchTo(player)
        syncRepeatModeOn(player)
    }

    fun detachServicePlayer(player: ExoPlayer?) {
        if (player != null && servicePlayer !== player) return
        try {
            servicePlayer?.removeListener(playerListener)
        } catch (_: Throwable) {
        }
        servicePlayer = null
        isTransitioningNext = false
        releaseTransitionWakeLock()
        _state.update { it.copy(isPlaying = false, isLoading = false) }
        BrookWidgetProvider.updateAllWidgets(context)
    }

    /**
     * Notificación segura entre hilos desde `ResolvingDataSource` al resolver calidad de audio.
     */
    fun onStreamQualityResolved(videoId: String, qualityLabel: String) {
        _state.update { st ->
            if (st.currentTrack?.id == videoId && st.qualityLabel != qualityLabel) {
                st.copy(qualityLabel = qualityLabel)
            } else {
                st
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                consecutiveAutoErrors = 0
                syncNextMediaItemInPlayer(alsoPrefetch = true)
            } else {
                persistCurrentSnapshot()
            }
            BrookWidgetProvider.updateAllWidgets(context)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                // Repetición nativa (Player.REPEAT_MODE_ONE) sin pasar nunca por STATE_ENDED
                countedPlayForTrackId = null
                listeningAccumMs = 0L
                skippedSegmentKeys.clear()
                _state.update { it.copy(positionMs = 0L) }
                return
            }
            if (mediaItem == null) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                commitNativeTransition(mediaItem)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = activePlayer() ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                Player.STATE_READY -> {
                    isTransitioningNext = false
                    releaseTransitionWakeLock()
                    consecutiveAutoErrors = 0
                    val dur = p.duration.takeIf { it > 0 }
                        ?: (_state.value.currentTrack?.duration?.times(1000L) ?: 0L)
                    _state.update { it.copy(isLoading = false, durationMs = dur, errorMsg = null) }
                    BrookWidgetProvider.updateAllWidgets(context)
                    syncNextMediaItemInPlayer(alsoPrefetch = true)
                }
                Player.STATE_ENDED -> {
                    if (_state.value.sleepEndOfTrack) {
                        isTransitioningNext = false
                        releaseTransitionWakeLock()
                        _state.update { it.copy(sleepEndOfTrack = false, isPlaying = false, isLoading = false) }
                        p.pause()
                        BrookWidgetProvider.updateAllWidgets(context)
                        return
                    }
                    if (_state.value.repeatMode == RepeatMode.TRACK) {
                        p.seekTo(0)
                        p.play()
                        return
                    }
                    // Respaldo si terminó justo antes de que Radio o la siguiente pista se encolaran en índice 1
                    if (hasAnotherTrackOrRadioAvailable()) {
                        isTransitioningNext = true
                        acquireTransitionWakeLock()
                        skipNext(autoAdvance = true)
                    } else {
                        isTransitioningNext = false
                        releaseTransitionWakeLock()
                        p.pause()
                        _state.update { it.copy(isPlaying = false, isLoading = false) }
                        BrookWidgetProvider.updateAllWidgets(context)
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = activePlayer()
            val cur = _state.value.currentTrack
            val savedPos = (p?.currentPosition ?: _state.value.positionMs).coerceAtLeast(0L)

            // 1. Si YouTube solo ofreció HLS para la pista (detectado por ResolvingDataSource)
            val hlsEx = findCause(error, HlsStreamRequiredException::class.java)
            if (hlsEx != null) {
                handleHlsRequiredError(hlsEx, p, cur, savedPos)
                return
            }

            // 2. Si falló la precarga de la pista SIGUIENTE (índice 1) mientras la actual aún suena:
            //    NO cortar la canción actual; retirar índice 1 y continuar reproduciendo.
            val resolveEx = findCause(error, TrackResolveFailedException::class.java)
            if (resolveEx != null && p != null && cur != null &&
                resolveEx.videoId != cur.id &&
                p.currentMediaItemIndex == 0 &&
                p.mediaItemCount > 1
            ) {
                failedPreloadIds.add(resolveEx.videoId)
                try {
                    p.removeMediaItems(1, p.mediaItemCount)
                    p.seekTo(0, savedPos)
                    p.prepare()
                    p.play()
                } catch (_: Throwable) {
                }
                return
            }

            // 3. Reintento automático de la pista actual renovando visitorData + HLS solo-audio
            if (cur != null && !retriedCurrent) {
                retriedCurrent = true
                isTransitioningNext = true
                acquireTransitionWakeLock()
                val retryPos = if (cur.duration > 0 && savedPos >= cur.duration * 1000L - 3000L) 0L else savedPos
                playTrack(
                    track = cur,
                    pushHistory = false,
                    forceFreshUrl = true,
                    preferHls = true,
                    startPositionMs = retryPos,
                )
                return
            }

            // 4. Si una pista concreta falla tras reintento y hay cola/radio, pasar a la siguiente (máx. 3 seguidas)
            if (consecutiveAutoErrors < MAX_CONSECUTIVE_AUTO_ERRORS && hasAnotherTrackOrRadioAvailable()) {
                consecutiveAutoErrors++
                isTransitioningNext = true
                acquireTransitionWakeLock()
                skipNext(autoAdvance = true)
                return
            }

            isTransitioningNext = false
            releaseTransitionWakeLock()
            val reason = shortPlaybackError(error)
            _state.update {
                it.copy(
                    isLoading = false,
                    isPlaying = false,
                    errorMsg = "Error de reproducción ($reason). Pulsa para reintentar.",
                )
            }
            BrookWidgetProvider.updateAllWidgets(context)
        }
    }

    private fun handleHlsRequiredError(
        hlsEx: HlsStreamRequiredException,
        p: Player?,
        cur: Track?,
        savedPos: Long,
    ) {
        if (p == null) return
        if (cur != null && hlsEx.videoId != cur.id && p.currentMediaItemIndex == 0 && p.mediaItemCount > 1) {
            // La pista SIGUIENTE (índice 1) requiere HLS mientras la actual sigue sonando
            val nextTrack = findTrackById(hlsEx.videoId)
            try {
                p.removeMediaItems(1, p.mediaItemCount)
                if (nextTrack != null) {
                    p.addMediaItem(
                        1,
                        buildMediaItemForTrack(nextTrack, Uri.parse(hlsEx.hlsUrl), isHls = true),
                    )
                }
                p.seekTo(0, savedPos)
                p.prepare()
                p.play()
            } catch (_: Throwable) {
            }
            return
        }

        // La pista actual (o recién transicionada) requiere HLS -> cargarla al instante sin otra llamada de red
        val targetTrack = if (cur?.id == hlsEx.videoId) cur else (findTrackById(hlsEx.videoId) ?: cur)
        if (targetTrack != null) {
            if (_state.value.currentTrack?.id != targetTrack.id) {
                val st = _state.value
                val qIdx = st.queue.indexOfFirst { it.id == targetTrack.id }
                val updatedQueue = if (qIdx >= 0) {
                    st.queue.toMutableList().apply { removeAt(qIdx) }
                } else {
                    val hIdx = history.indexOfFirst { it.id == targetTrack.id }
                    if (hIdx >= 0) history.removeAt(hIdx)
                    st.queue
                }
                if (cur != null && cur.id != targetTrack.id) {
                    history.addLast(cur)
                    while (history.size > 50) history.removeFirst()
                }
                commitTrackMetadataChange(targetTrack, updatedQueue)
            }
            val nextTrack = computeNextTrackForPreload()?.takeIf { it.id !in failedPreloadIds }
            val items = buildList {
                add(buildMediaItemForTrack(targetTrack, Uri.parse(hlsEx.hlsUrl), isHls = true))
                if (nextTrack != null) add(buildMediaItemForTrack(nextTrack))
            }
            val safeStart = if (targetTrack.duration > 0 && savedPos >= targetTrack.duration * 1000L - 3000L) 0L else savedPos
            _state.update { it.copy(qualityLabel = hlsEx.qualityLabel, errorMsg = null) }
            syncRepeatModeOn(p)
            applySpeedPitchTo(p)
            p.setMediaItems(items, 0, safeStart)
            p.prepare()
            p.play()
        }
    }

    private fun <T : Throwable> findCause(error: Throwable, clazz: Class<T>): T? {
        var c: Throwable? = error
        var depth = 0
        while (c != null && depth < 8) {
            if (clazz.isInstance(c)) return clazz.cast(c)
            c = c.cause
            depth++
        }
        return null
    }

    private fun shortPlaybackError(error: PlaybackException): String {
        val httpEx = findCause(error, HttpDataSource.InvalidResponseCodeException::class.java)
        if (httpEx != null) return "HTTP ${httpEx.responseCode}"
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Sin conexión"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Tiempo agotado"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP bloqueado"
            else -> error.errorCodeName.removePrefix("ERROR_CODE_")
        }
    }

    init {
        val st = library.state.value.settings
        audioEffects.setSkipSilence(st.skipSilence)
        audioEffects.setSmoothFade(st.smoothFade)
        connectController()
        startPositionTicker()
    }

    private fun applySpeedPitchTo(p: Player) {
        val fx = audioEffects.state.value
        try {
            p.playbackParameters = PlaybackParameters(fx.playbackSpeed, fx.playbackPitch)
        } catch (_: Throwable) {
        }
    }

    private fun syncRepeatModeOn(p: Player) {
        val nativeRepeat = if (!_state.value.sleepEndOfTrack && _state.value.repeatMode == RepeatMode.TRACK) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        try {
            if (p.repeatMode != nativeRepeat) {
                p.repeatMode = nativeRepeat
            }
        } catch (_: Throwable) {
        }
    }

    fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        audioEffects.setSpeedAndPitch(speed, pitch)
        activePlayer()?.let { applySpeedPitchTo(it) }
    }

    fun setSkipSilence(enabled: Boolean) {
        audioEffects.setSkipSilence(enabled)
        library.updateSkipSilence(enabled)
    }

    fun setSmoothFade(enabled: Boolean) {
        audioEffects.setSmoothFade(enabled)
        library.updateSmoothFade(enabled)
    }

    private fun persistCurrentSnapshot() {
        val st = _state.value
        val cur = st.currentTrack ?: return
        library.savePlaybackSnapshot(cur, st.positionMs, st.queue)
        lastSnapshotSavedMs = System.currentTimeMillis()
    }

    private fun hasAnotherTrackOrRadioAvailable(): Boolean {
        val st = _state.value
        return st.queue.isNotEmpty() ||
            (st.repeatMode == RepeatMode.PLAYLIST && history.isNotEmpty()) ||
            (st.autoRadio && st.currentTrack != null)
    }

    private fun findTrackById(videoId: String): Track? {
        _state.value.queue.firstOrNull { it.id == videoId }?.let { return it }
        history.firstOrNull { it.id == videoId }?.let { return it }
        if (_state.value.currentTrack?.id == videoId) return _state.value.currentTrack
        return null
    }

    /**
     * Calcula qué canción debe ir precargada en el índice 1 de ExoPlayer para transición sin cortes.
     * Devuelve `null` si está activo Temporizador "Al acabar" o Repetir canción (que usa REPEAT_MODE_ONE nativo).
     */
    private fun computeNextTrackForPreload(): Track? {
        val st = _state.value
        if (st.sleepEndOfTrack || st.repeatMode == RepeatMode.TRACK) {
            plannedNextTrackId = null
            return null
        }
        val q = st.queue
        if (q.isNotEmpty()) {
            if (!st.shuffle) {
                val first = q.first()
                plannedNextTrackId = first.id
                return first
            }
            val existingPlanned = plannedNextTrackId?.let { id -> q.firstOrNull { it.id == id } }
            if (existingPlanned != null) return existingPlanned
            val chosen = q[Random.nextInt(q.size)]
            plannedNextTrackId = chosen.id
            return chosen
        }
        if (st.repeatMode == RepeatMode.PLAYLIST && history.isNotEmpty()) {
            val first = history.first()
            plannedNextTrackId = first.id
            return first
        }
        plannedNextTrackId = null
        return null
    }

    private fun buildMediaItemForTrack(
        track: Track,
        explicitUri: Uri? = null,
        isHls: Boolean = false,
    ): MediaItem {
        val resolvedUri: Uri
        val resolvedIsHls: Boolean
        if (explicitUri != null) {
            resolvedUri = explicitUri
            resolvedIsHls = isHls
        } else {
            val localFile = offlineCache.getLocalFile(track.id)
            val cached = api.getCachedStream(track.id, preferHls = false)
            when {
                localFile != null -> {
                    resolvedUri = Uri.fromFile(localFile)
                    resolvedIsHls = false
                }
                cached != null && NoctaliaPlaybackService.isHlsUrlOrMime(cached.url, cached.mimeType) -> {
                    resolvedUri = Uri.parse(cached.url)
                    resolvedIsHls = true
                }
                else -> {
                    // URI interna segura resuelta en el hilo Loader de ExoPlayer mediante ResolvingDataSource
                    resolvedUri = NoctaliaPlaybackService.buildBrookTrackUri(track.id)
                    resolvedIsHls = false
                }
            }
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifEmpty { "Brook" })
        if (track.thumb.isNotBlank()) {
            metadataBuilder.setArtworkUri(Uri.parse(track.thumb))
        }

        val itemBuilder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(resolvedUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(resolvedUri)
                    .build()
            )
            .setMediaMetadata(metadataBuilder.build())
        if (resolvedIsHls) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return itemBuilder.build()
    }

    /**
     * Mantiene siempre sincronizada la siguiente canción dentro de la línea temporal nativa de ExoPlayer
     * (`[canción actual, siguiente canción]`) para que al acabar una canción en segundo plano
     * ExoPlayer pase directamente a la siguiente sin caer nunca en `STATE_ENDED` ni perder Foreground.
     */
    private fun syncNextMediaItemInPlayer(alsoPrefetch: Boolean = true) {
        val p = activePlayer() ?: return
        syncRepeatModeOn(p)
        if (p.mediaItemCount == 0) return

        val curIdx = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
        val desiredNext = computeNextTrackForPreload()

        try {
            if (desiredNext == null || desiredNext.id in failedPreloadIds) {
                if (curIdx + 1 < p.mediaItemCount) {
                    p.removeMediaItems(curIdx + 1, p.mediaItemCount)
                }
                return
            }

            val alreadyQueued = curIdx + 1 < p.mediaItemCount &&
                p.getMediaItemAt(curIdx + 1).mediaId == desiredNext.id
            if (alreadyQueued) {
                if (p.mediaItemCount > curIdx + 2) {
                    p.removeMediaItems(curIdx + 2, p.mediaItemCount)
                }
            } else {
                if (curIdx + 1 < p.mediaItemCount) {
                    p.removeMediaItems(curIdx + 1, p.mediaItemCount)
                }
                p.addMediaItem(curIdx + 1, buildMediaItemForTrack(desiredNext))
            }
        } catch (_: Throwable) {
        }

        if (alsoPrefetch) {
            prefetchNextInQueue(desiredNext)
        }
    }

    private fun prefetchNextInQueue(explicitNext: Track? = null) {
        val next = explicitNext ?: computeNextTrackForPreload() ?: return
        if (offlineCache.isCached(next.id)) return
        if (next.id == prefetchedTrackId && api.getCachedStream(next.id) != null) return
        prefetchedTrackId = next.id
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                val resolved = api.resolveStreamUrl(next.id, forceRefresh = false, preferHls = false) ?: return@launch
                // Si la siguiente pista solo está en HLS, actualizar su MediaItem en índice 1 con MimeTypes.APPLICATION_M3U8
                if (NoctaliaPlaybackService.isHlsUrlOrMime(resolved.url, resolved.mimeType)) {
                    val p = activePlayer() ?: return@launch
                    val curIdx = p.currentMediaItemIndex
                    if (curIdx >= 0 && curIdx + 1 < p.mediaItemCount &&
                        p.getMediaItemAt(curIdx + 1).mediaId == next.id
                    ) {
                        val existingMime = p.getMediaItemAt(curIdx + 1).localConfiguration?.mimeType
                        if (existingMime != MimeTypes.APPLICATION_M3U8) {
                            p.removeMediaItem(curIdx + 1)
                            p.addMediaItem(
                                curIdx + 1,
                                buildMediaItemForTrack(next, Uri.parse(resolved.url), isHls = true),
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Ejecutado cuando ExoPlayer avanza nativamente a la canción precargada en índice 1
     * (con pantalla apagada o en segundo plano, sin interrumpir nunca Foreground ni notificación).
     */
    private fun commitNativeTransition(mediaItem: MediaItem) {
        val newId = mediaItem.mediaId
        if (newId.isBlank() || newId == _state.value.currentTrack?.id) return

        val st = _state.value
        val qIdx = st.queue.indexOfFirst { it.id == newId }
        val nextTrack: Track
        val updatedQueue: List<Track>

        if (qIdx >= 0) {
            nextTrack = st.queue[qIdx]
            updatedQueue = st.queue.toMutableList().apply { removeAt(qIdx) }
        } else {
            val hIdx = history.indexOfFirst { it.id == newId }
            if (hIdx >= 0) {
                nextTrack = history.removeAt(hIdx)
            } else {
                val meta = mediaItem.mediaMetadata
                nextTrack = Track(
                    id = newId,
                    title = meta.title?.toString().orEmpty().ifBlank { "Canción" },
                    artist = meta.artist?.toString().orEmpty().ifBlank { "YouTube Music" },
                    album = meta.albumTitle?.toString().orEmpty(),
                    thumb = meta.artworkUri?.toString().orEmpty(),
                )
            }
            updatedQueue = st.queue
        }

        val prev = st.currentTrack
        if (prev != null && prev.id != nextTrack.id) {
            history.addLast(prev)
            while (history.size > 50) history.removeFirst()
        }

        commitTrackMetadataChange(nextTrack, updatedQueue)

        // Mantener la línea temporal de ExoPlayer ligera: eliminar la pista anterior (índice 0)
        // y encolar inmediatamente la nueva pista siguiente en índice 1.
        val p = activePlayer()
        if (p != null) {
            try {
                if (p.currentMediaItemIndex > 0) {
                    p.removeMediaItems(0, p.currentMediaItemIndex)
                }
                p.volume = 1f
            } catch (_: Throwable) {
            }
        }

        if (_state.value.autoRadio && _state.value.queue.isEmpty()) {
            prefetchRadio(nextTrack.id, playImmediately = false)
        }
        syncNextMediaItemInPlayer(alsoPrefetch = true)
    }

    private fun commitTrackMetadataChange(nextTrack: Track, updatedQueue: List<Track>? = null) {
        library.recordRecent(nextTrack)
        retriedCurrent = false
        countedPlayForTrackId = null
        listeningAccumMs = 0L
        activeSkipSegments = emptyList()
        skippedSegmentKeys.clear()
        failedPreloadIds.clear()
        prefetchedTrackId = null
        plannedNextTrackId = null

        val quality = when {
            offlineCache.isCached(nextTrack.id) -> "OFFLINE · OPUS"
            else -> api.getCachedStream(nextTrack.id)?.qualityLabel ?: _state.value.qualityLabel
        }
        val p = activePlayer()
        val dur = p?.duration?.takeIf { it > 0 } ?: (nextTrack.duration * 1000L)

        _state.update { cur ->
            cur.copy(
                currentTrack = nextTrack,
                queue = updatedQueue ?: cur.queue,
                isLoading = p?.playbackState == Player.STATE_BUFFERING,
                positionMs = 0L,
                durationMs = dur,
                qualityLabel = quality,
                lyrics = LyricsResult(),
                loadingLyrics = true,
                sponsorSkippedNotice = null,
                errorMsg = null,
            )
        }
        library.savePlaybackSnapshot(nextTrack, 0L, _state.value.queue)
        lastSnapshotSavedMs = System.currentTimeMillis()
        BrookWidgetProvider.updateAllWidgets(context)
        launchMetadataJobsFor(nextTrack)
    }

    private fun launchMetadataJobsFor(clean: Track) {
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
    }

    private fun fadeInVolume(p: Player) {
        fadeJob?.cancel()
        isFadingOutToPause = false
        if (!audioEffects.state.value.smoothFadeEnabled) {
            try { p.volume = 1f } catch (_: Throwable) {}
            return
        }
        fadeJob = scope.launch {
            try {
                val steps = 8
                for (i in 1..steps) {
                    if (activePlayer() !== p) return@launch
                    p.volume = i.toFloat() / steps.toFloat()
                    delay(30L)
                }
            } finally {
                try {
                    if (activePlayer() === p) p.volume = 1f
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun fadeOutAndPause(p: Player) {
        fadeJob?.cancel()
        if (!audioEffects.state.value.smoothFadeEnabled) {
            isFadingOutToPause = false
            p.pause()
            p.volume = 1f
            return
        }
        isFadingOutToPause = true
        fadeJob = scope.launch {
            try {
                for (i in 6 downTo 1) {
                    if (activePlayer() !== p || !p.isPlaying) break
                    p.volume = i / 7f
                    delay(28L)
                }
                if (activePlayer() === p) p.pause()
            } finally {
                isFadingOutToPause = false
                try {
                    if (activePlayer() === p) p.volume = 1f
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun connectController() {
        scope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, NoctaliaPlaybackService::class.java))
                val ctrl = MediaController.Builder(context, token).buildAsync().await()
                mediaController = ctrl
                if (servicePlayer == null) {
                    ctrl.addListener(playerListener)
                    applySpeedPitchTo(ctrl)
                    syncRepeatModeOn(ctrl)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun ensureActivePlayer(): Player? {
        if (servicePlayer != null) {
            if (mediaController?.isConnected != true) {
                connectController()
            }
            return servicePlayer
        }
        mediaController?.takeIf { it.isConnected }?.let { return it }
        try {
            mediaController?.removeListener(playerListener)
        } catch (_: Throwable) {
        }
        mediaController = null
        return try {
            val token = SessionToken(context, ComponentName(context, NoctaliaPlaybackService::class.java))
            val ctrl = MediaController.Builder(context, token).buildAsync().await()
            mediaController = ctrl
            val sp = servicePlayer
            if (sp != null) {
                sp
            } else {
                ctrl.addListener(playerListener)
                applySpeedPitchTo(ctrl)
                syncRepeatModeOn(ctrl)
                ctrl
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                val p = activePlayer()
                val curTrack = _state.value.currentTrack
                val playing = p?.isPlaying == true
                if (p != null && curTrack != null && p.mediaItemCount > 0) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it > 0 }
                        ?: (curTrack.duration * 1000L)

                    // SponsorBlock: saltar intros/segmentos no musicales automáticamente
                    if (playing && audioEffects.state.value.sponsorBlockEnabled && activeSkipSegments.isNotEmpty()) {
                        val hit = activeSkipSegments.firstOrNull { seg ->
                            val key = "${seg.startMs}_${seg.endMs}"
                            key !in skippedSegmentKeys && pos >= seg.startMs && pos < (seg.endMs - 800L)
                        }
                        if (hit != null) {
                            skippedSegmentKeys.add("${hit.startMs}_${hit.endMs}")
                            val target = hit.endMs.coerceAtMost((dur - 500L).coerceAtLeast(0L))
                            p.seekTo(target)
                            _state.update { it.copy(sponsorSkippedNotice = "Saltado segmento no musical (SponsorBlock)") }
                            scope.launch {
                                delay(2600L)
                                _state.update { it.copy(sponsorSkippedNotice = null) }
                            }
                        }
                    }

                    // Brook Stats locales + guardado periódico de sesión + garantía de pre-carga a 25s del final
                    if (playing) {
                        listeningAccumMs += 280L
                        if (countedPlayForTrackId != curTrack.id && listeningAccumMs >= 5_000L) {
                            countedPlayForTrackId = curTrack.id
                            library.recordTrackPlayStat(curTrack, incrementPlayCount = true, addSeconds = 5L)
                            listeningAccumMs = 0L
                        } else if (listeningAccumMs >= 10_000L) {
                            library.recordTrackPlayStat(curTrack, incrementPlayCount = false, addSeconds = 10L)
                            listeningAccumMs = 0L
                        }

                        if (dur > 0L && (dur - pos) in 1L..25_000L) {
                            syncNextMediaItemInPlayer(alsoPrefetch = true)
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastSnapshotSavedMs >= 8_000L) {
                            library.savePlaybackSnapshot(curTrack, pos, _state.value.queue)
                            lastSnapshotSavedMs = now
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
                delay(if (playing) 280L else 1000L)
            }
        }
    }

    fun playTrack(
        track: Track,
        pushHistory: Boolean = true,
        forceFreshUrl: Boolean = false,
        preferHls: Boolean = false,
        startPositionMs: Long = 0L,
    ) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        val prev = _state.value.currentTrack
        if (pushHistory && prev != null && prev.id != clean.id) {
            history.addLast(prev)
            while (history.size > 50) history.removeFirst()
        }

        library.recordRecent(clean)
        if (!forceFreshUrl) {
            retriedCurrent = false
            consecutiveAutoErrors = 0
        }
        if (prev?.id != clean.id) {
            countedPlayForTrackId = null
            listeningAccumMs = 0L
        }
        activeSkipSegments = emptyList()
        skippedSegmentKeys.clear()
        failedPreloadIds.clear()
        prefetchedTrackId = null
        val initialPos = startPositionMs.coerceAtLeast(0L)

        val initialQuality = when {
            !forceFreshUrl && offlineCache.isCached(clean.id) -> "OFFLINE · OPUS"
            !forceFreshUrl -> api.getCachedStream(clean.id, preferHls)?.qualityLabel ?: "OPUS 160k"
            else -> _state.value.qualityLabel
        }

        _state.update {
            it.copy(
                currentTrack = clean,
                isLoading = true,
                positionMs = initialPos,
                durationMs = clean.duration * 1000L,
                qualityLabel = initialQuality,
                lyrics = LyricsResult(),
                loadingLyrics = true,
                sponsorSkippedNotice = null,
                errorMsg = null,
            )
        }
        library.savePlaybackSnapshot(clean, initialPos, _state.value.queue)
        lastSnapshotSavedMs = System.currentTimeMillis()
        BrookWidgetProvider.updateAllWidgets(context)

        launchMetadataJobsFor(clean)

        if (_state.value.autoRadio && _state.value.queue.isEmpty()) {
            prefetchRadio(clean.id)
        }

        isTransitioningNext = true
        acquireTransitionWakeLock()

        playJob?.cancel()
        playJob = scope.launch {
            val p = ensureActivePlayer()
            if (p == null) {
                isTransitioningNext = false
                releaseTransitionWakeLock()
                _state.update { it.copy(isLoading = false, errorMsg = "Servicio de audio no disponible.") }
                return@launch
            }
            if (_state.value.currentTrack?.id != clean.id) return@launch

            val primaryItem: MediaItem
            if (forceFreshUrl || preferHls) {
                // Reintento explícito: invalidar caché y resolver fresh URL / HLS solo-audio
                val resolved = api.resolveStreamUrl(
                    videoId = clean.id,
                    forceRefresh = forceFreshUrl,
                    preferHls = preferHls,
                )
                if (_state.value.currentTrack?.id != clean.id) return@launch
                if (resolved == null) {
                    if (consecutiveAutoErrors < MAX_CONSECUTIVE_AUTO_ERRORS && hasAnotherTrackOrRadioAvailable()) {
                        consecutiveAutoErrors++
                        skipNext(autoAdvance = true)
                    } else {
                        isTransitioningNext = false
                        releaseTransitionWakeLock()
                        _state.update {
                            it.copy(
                                isLoading = false,
                                isPlaying = false,
                                errorMsg = "No se pudo cargar el audio de esta canción.",
                            )
                        }
                        BrookWidgetProvider.updateAllWidgets(context)
                    }
                    return@launch
                }
                val isHls = NoctaliaPlaybackService.isHlsUrlOrMime(resolved.url, resolved.mimeType)
                _state.update { it.copy(qualityLabel = resolved.qualityLabel) }
                primaryItem = buildMediaItemForTrack(clean, Uri.parse(resolved.url), isHls = isHls)
            } else {
                // Ruta rápida y segura en segundo plano: encolar instantáneamente en ExoPlayer
                // (ResolvingDataSource resuelve en el hilo Loader manteniendo Foreground + STATE_BUFFERING)
                primaryItem = buildMediaItemForTrack(clean)
            }

            val nextTrack = computeNextTrackForPreload()?.takeIf { it.id !in failedPreloadIds }
            val items = buildList {
                add(primaryItem)
                if (nextTrack != null) add(buildMediaItemForTrack(nextTrack))
            }

            fadeJob?.cancel()
            isFadingOutToPause = false
            syncRepeatModeOn(p)
            applySpeedPitchTo(p)
            p.volume = if (audioEffects.state.value.smoothFadeEnabled && !forceFreshUrl) 0.1f else 1f
            val safeStart = if (clean.duration > 0 && initialPos >= clean.duration * 1000L - 3000L) 0L else initialPos
            p.setMediaItems(items, 0, safeStart)
            p.prepare()
            p.play()
            if (!forceFreshUrl) {
                fadeInVolume(p)
            } else {
                p.volume = 1f
            }
            if (nextTrack != null) {
                prefetchNextInQueue(nextTrack)
            }
        }
    }

    fun playTrackList(tracks: List<Track>, startIndex: Int = 0) {
        val clean = tracks.mapNotNull { SecurityValidators.sanitizeTrack(it) }
        if (clean.isEmpty()) return
        val idx = startIndex.coerceIn(0, clean.lastIndex)
        val selected = clean[idx]
        val rest = clean.drop(idx + 1)
        plannedNextTrackId = null
        _state.update { it.copy(queue = rest) }
        playTrack(selected, pushHistory = true)
    }

    fun togglePlayPause() {
        if (_state.value.currentTrack == null) {
            val rec = library.state.value.recent.firstOrNull()
            if (rec != null) playTrack(rec, pushHistory = false)
            return
        }
        scope.launch {
            val p = ensureActivePlayer() ?: return@launch
            if (isFadingOutToPause) {
                fadeJob?.cancel()
                isFadingOutToPause = false
                p.volume = 1f
                if (!p.isPlaying) p.play()
                return@launch
            }
            if (p.isPlaying) {
                fadeOutAndPause(p)
            } else {
                fadeJob?.cancel()
                applySpeedPitchTo(p)
                syncRepeatModeOn(p)
                if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED || p.mediaItemCount == 0) {
                    val resumePos = if (p.mediaItemCount == 0) _state.value.positionMs else 0L
                    _state.value.currentTrack?.let {
                        playTrack(it, pushHistory = false, startPositionMs = resumePos)
                    }
                } else {
                    p.volume = if (audioEffects.state.value.smoothFadeEnabled) 0.1f else 1f
                    p.play()
                    fadeInVolume(p)
                    syncNextMediaItemInPlayer(alsoPrefetch = true)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch {
            val p = ensureActivePlayer() ?: return@launch
            val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(1L))
            p.seekTo(clamped)
            _state.update { it.copy(positionMs = clamped) }
        }
    }

    fun skipNext(autoAdvance: Boolean = false) {
        val curQueue = _state.value.queue
        if (curQueue.isNotEmpty()) {
            val plannedIdx = plannedNextTrackId?.let { id -> curQueue.indexOfFirst { it.id == id } } ?: -1
            val idx = when {
                plannedIdx in curQueue.indices -> plannedIdx
                _state.value.shuffle -> Random.nextInt(curQueue.size)
                else -> 0
            }
            val next = curQueue[idx]

            // Si ExoPlayer real ya tiene exactamente esta canción precargada en índice 1 y está activo,
            // saltar nativamente (0 ms, mantiene Foreground y usa el buffer ya descargado).
            // IMPORTANTE: usar solo `servicePlayer` (nunca `mediaController`) para evitar recursión en ForwardingPlayer.
            val sp = servicePlayer
            if (!autoAdvance && sp != null &&
                sp.playbackState != Player.STATE_IDLE &&
                sp.playbackState != Player.STATE_ENDED &&
                sp.currentMediaItemIndex == 0 &&
                sp.mediaItemCount > 1 &&
                sp.getMediaItemAt(1).mediaId == next.id
            ) {
                try {
                    sp.seekToNextMediaItem()
                    if (!sp.playWhenReady) sp.play()
                    return
                } catch (_: Throwable) {
                }
            }

            val remaining = curQueue.toMutableList().apply { removeAt(idx) }
            plannedNextTrackId = null
            _state.update { it.copy(queue = remaining) }
            playTrack(next, pushHistory = true)
            return
        }

        if (_state.value.repeatMode == RepeatMode.PLAYLIST && history.isNotEmpty()) {
            val first = history.removeFirst()
            plannedNextTrackId = null
            playTrack(first, pushHistory = true)
            return
        }

        val cur = _state.value.currentTrack
        if (_state.value.autoRadio && cur != null) {
            prefetchRadio(cur.id, playImmediately = true)
            return
        }

        if (autoAdvance) {
            isTransitioningNext = false
            releaseTransitionWakeLock()
            activePlayer()?.pause()
            _state.update { it.copy(isPlaying = false, isLoading = false) }
        }
    }

    fun skipPrevious() {
        val p = activePlayer()
        if (p != null && p.currentPosition > 4000L) {
            seekTo(0L)
            return
        }
        if (history.isNotEmpty()) {
            val prev = history.removeLast()
            val cur = _state.value.currentTrack
            if (cur != null) {
                plannedNextTrackId = cur.id
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
        syncNextMediaItemInPlayer(alsoPrefetch = false)
        sleepJob = scope.launch {
            while (rem > 0) {
                delay(1000L)
                rem -= 1
                _state.update { it.copy(sleepRemainingSec = rem) }
            }
            // Fade-out suave de 2 segundos antes de pausar
            fadeJob?.cancel()
            isFadingOutToPause = false
            val p = activePlayer()
            if (p != null && p.isPlaying) {
                for (step in 9 downTo 1) {
                    if (activePlayer() !== p) break
                    p.volume = step / 10f
                    delay(200L)
                }
                if (activePlayer() === p) {
                    p.pause()
                    p.volume = 1f
                }
            }
            _state.update { it.copy(sleepRemainingSec = null, sleepEndOfTrack = false) }
        }
    }

    fun setSleepAtEndOfTrack() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepRemainingSec = null, sleepEndOfTrack = true) }
        // Retirar índice 1 y desactivar REPEAT_MODE_ONE para que se pare limpiamente al acabar esta canción
        syncNextMediaItemInPlayer(alsoPrefetch = false)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        try {
            activePlayer()?.volume = 1f
        } catch (_: Throwable) {
        }
        _state.update { it.copy(sleepRemainingSec = null, sleepEndOfTrack = false) }
        syncNextMediaItemInPlayer(alsoPrefetch = true)
    }

    fun addToQueue(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        _state.update { it.copy(queue = it.queue + clean) }
        persistCurrentSnapshot()
        syncNextMediaItemInPlayer(alsoPrefetch = true)
    }

    fun addPlayNext(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        plannedNextTrackId = clean.id
        _state.update { st ->
            val filtered = st.queue.filterNot { it.id == clean.id }
            st.copy(queue = listOf(clean) + filtered)
        }
        persistCurrentSnapshot()
        syncNextMediaItemInPlayer(alsoPrefetch = true)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val curQueue = _state.value.queue
        if (fromIndex !in curQueue.indices || toIndex !in curQueue.indices || fromIndex == toIndex) return
        val updated = curQueue.toMutableList()
        val moved = updated.removeAt(fromIndex)
        updated.add(toIndex, moved)
        if (fromIndex == 0 || toIndex == 0) {
            plannedNextTrackId = null
        }
        _state.update { it.copy(queue = updated) }
        persistCurrentSnapshot()
        if (fromIndex == 0 || toIndex == 0) {
            syncNextMediaItemInPlayer(alsoPrefetch = true)
        }
    }

    fun startRadioFromTrack(track: Track) {
        val clean = SecurityValidators.sanitizeTrack(track) ?: return
        radioJob?.cancel()
        pendingPlayRadioWhenReady = false
        plannedNextTrackId = null
        _state.update { it.copy(queue = emptyList()) }
        playTrack(clean, pushHistory = true)
        prefetchRadio(clean.id, playImmediately = false)
    }

    fun playFromQueue(index: Int) {
        val curQueue = _state.value.queue
        if (index !in curQueue.indices) return
        val chosen = curQueue[index]
        val rest = curQueue.toMutableList().apply { removeAt(index) }
        plannedNextTrackId = null
        _state.update { it.copy(queue = rest) }
        playTrack(chosen, pushHistory = true)
    }

    fun removeFromQueue(index: Int) {
        val curQueue = _state.value.queue
        if (index !in curQueue.indices) return
        val removed = curQueue[index]
        val rest = curQueue.toMutableList().apply { removeAt(index) }
        if (removed.id == plannedNextTrackId) plannedNextTrackId = null
        _state.update { it.copy(queue = rest) }
        persistCurrentSnapshot()
        if (index == 0 || removed.id == prefetchedTrackId) {
            syncNextMediaItemInPlayer(alsoPrefetch = true)
        }
    }

    fun clearQueue() {
        plannedNextTrackId = null
        _state.update { it.copy(queue = emptyList()) }
        persistCurrentSnapshot()
        syncNextMediaItemInPlayer(alsoPrefetch = false)
    }

    fun toggleShuffle() {
        plannedNextTrackId = null
        _state.update { it.copy(shuffle = !it.shuffle) }
        syncNextMediaItemInPlayer(alsoPrefetch = true)
    }

    fun cycleRepeat() {
        plannedNextTrackId = null
        _state.update {
            val nxt = when (it.repeatMode) {
                RepeatMode.NONE -> RepeatMode.PLAYLIST
                RepeatMode.PLAYLIST -> RepeatMode.TRACK
                RepeatMode.TRACK -> RepeatMode.NONE
            }
            it.copy(repeatMode = nxt)
        }
        syncNextMediaItemInPlayer(alsoPrefetch = true)
    }

    fun setAutoRadio(enabled: Boolean) {
        library.updateAutoRadio(enabled)
        _state.update { it.copy(autoRadio = enabled) }
        if (enabled && _state.value.queue.isEmpty()) {
            _state.value.currentTrack?.let { prefetchRadio(it.id) }
        }
    }

    private fun prefetchRadio(seedVideoId: String, playImmediately: Boolean = false) {
        // Si ya hay una petición de Radio en vuelo para esta misma canción, aprovecharla en vez de duplicarla
        if (radioJob?.isActive == true && radioSeedVideoId == seedVideoId) {
            if (playImmediately) {
                pendingPlayRadioWhenReady = true
                isTransitioningNext = true
                acquireTransitionWakeLock()
                _state.update { it.copy(isLoading = true) }
            }
            return
        }
        radioJob?.cancel()
        radioSeedVideoId = seedVideoId
        pendingPlayRadioWhenReady = playImmediately
        if (playImmediately) {
            isTransitioningNext = true
            acquireTransitionWakeLock()
        }
        radioJob = scope.launch {
            try {
                if (pendingPlayRadioWhenReady) _state.update { it.copy(isLoading = true) }
                val radioTracks = api.getRadio(seedVideoId, limit = 20)
                val existingIds = (_state.value.queue.map { it.id } + listOfNotNull(_state.value.currentTrack?.id)).toSet()
                val fresh = radioTracks.filterNot { it.id in existingIds }.take(15)
                val shouldPlayNow = pendingPlayRadioWhenReady
                pendingPlayRadioWhenReady = false
                if (fresh.isNotEmpty()) {
                    if (shouldPlayNow) {
                        plannedNextTrackId = null
                        _state.update { it.copy(queue = it.queue + fresh.drop(1)) }
                        playTrack(fresh.first(), pushHistory = true)
                    } else {
                        _state.update { it.copy(queue = it.queue + fresh) }
                        persistCurrentSnapshot()
                        syncNextMediaItemInPlayer(alsoPrefetch = true)
                    }
                } else if (shouldPlayNow) {
                    isTransitioningNext = false
                    releaseTransitionWakeLock()
                    _state.update { it.copy(isLoading = false, isPlaying = false) }
                }
            } catch (_: Throwable) {
                if (pendingPlayRadioWhenReady) {
                    pendingPlayRadioWhenReady = false
                    isTransitioningNext = false
                    releaseTransitionWakeLock()
                    _state.update { it.copy(isLoading = false, isPlaying = false) }
                }
            }
        }
    }

    companion object {
        private const val MAX_CONSECUTIVE_AUTO_ERRORS = 3
    }
}
