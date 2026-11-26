package com.noctalia.music.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.noctalia.music.MainActivity
import com.noctalia.music.NoctaliaMusicApp
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.Track
import com.noctalia.music.data.YTMusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Señal interna lanzada por `ResolvingDataSource` cuando YouTube solo ofrece HLS para una pista,
 * permitiendo que el reproductor cambie inmediatamente a `HlsMediaSource` sin perder Foreground.
 */
class HlsStreamRequiredException(
    val videoId: String,
    val hlsUrl: String,
    val qualityLabel: String,
) : IOException("HLS stream required for $videoId")

/**
 * Error tipado que identifica exactamente qué `videoId` falló al resolverse en `ResolvingDataSource`,
 * impidiendo que un fallo al precargar la pista siguiente interrumpa la pista que está sonando.
 */
class TrackResolveFailedException(
    val videoId: String,
    cause: Throwable? = null,
) : IOException("Failed to resolve audio stream for $videoId", cause)

/**
 * Servicio en primer plano (Media3 `MediaLibraryService`) con:
 * - Reproducción continua sin cortes en segundo plano (`ResolvingDataSource` + `WAKE_MODE_NETWORK`).
 * - Doble deck ExoPlayer (`activeDeck` + `secondaryDeck`) para **Crossfade real solapado** entre pistas.
 * - Reproducción nativa de archivos locales (`brook-track://local/<mediaId>` -> `MediaStore` content URI).
 * - Soporte completo de **Android Auto** (navegación por Favoritas, Recientes, Playlists, Locales y búsqueda por voz).
 * - Aislamiento estricto: ningún cliente externo puede inyectar URLs arbitrarias.
 */
class NoctaliaPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var activeDeck: ExoPlayer? = null
    private var secondaryDeck: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val searchCacheLock = Any()
    private val androidAutoSearchCache = object : LinkedHashMap<String, List<Track>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Track>>?): Boolean {
            return size > MAX_AA_SEARCH_CACHE
        }
    }

    private fun putSearchCache(query: String, tracks: List<Track>) {
        synchronized(searchCacheLock) {
            androidAutoSearchCache[query] = tracks
        }
    }

    private fun getSearchCache(query: String): List<Track> {
        synchronized(searchCacheLock) {
            return androidAutoSearchCache[query].orEmpty()
        }
    }

    private fun allSearchCachedTracks(): List<Track> {
        synchronized(searchCacheLock) {
            return androidAutoSearchCache.values.flatten()
        }
    }

    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean {
        val pkg = controller.packageName
        if (pkg == packageName) return true
        if (controller.isTrusted) return true
        return pkg in ALLOWED_EXTERNAL_MEDIA_PACKAGES
    }

    @OptIn(UnstableApi::class)
    private fun createDeck(
        resolvingFactory: ResolvingDataSource.Factory,
        audioAttrs: AudioAttributes,
        handleFocus: Boolean,
        sharedAudioSessionId: Int,
    ): ExoPlayer {
        // RenderersFactory afinado para MÚSICA (evita el efecto "acelerado" en partes suaves al activar
        // «Saltar silencios» y evita acelerones de recuperación de AudioTrack en Bluetooth/HyperOS):
        // - SilenceSkippingAudioProcessor calibrado para música: solo silencio real (>650 ms y <-46 dBFS)
        // - Buffer PCM holgado (500–1000 ms) para absorber latencia Bluetooth A2DP sin micro-underruns
        // - LivePlaybackSpeedControl bloqueado a 1.0x para que HLS jamás altere la velocidad automáticamente
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                // IMPORTANTE en Media3 1.6.1: `maxSilenceToKeepDurationUs` debe ser SIEMPRE estrictamente
                // mayor que `minimumSilenceDurationUs` (Media3 usa 2_000_000L por defecto); si es menor,
                // `calculateShortenedSilenceLength` lanza IllegalStateException al primer silencio y salta de pista.
                val musicSilenceSkipper = SilenceSkippingAudioProcessor(
                    /* minimumSilenceDurationUs = */ 600_000L,
                    /* silenceRetentionRatio = */ 0.15f,
                    /* maxSilenceToKeepDurationUs = */ SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                    /* minVolumeToKeepPercentageWhenMuting = */ SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                    /* silenceThresholdLevel = */ 256.toShort(),
                )
                val processorChain = DefaultAudioSink.DefaultAudioProcessorChain(
                    emptyArray(),
                    musicSilenceSkipper,
                    SonicAudioProcessor(),
                )
                val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(500_000)
                    .setMaxPcmBufferDurationUs(1_000_000)
                    .build()
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(processorChain)
                    .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                    .build()
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 2_500)
            .build()
        val fixedSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            .build()

        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(fixedSpeedControl)
            .setAudioAttributes(audioAttrs, handleFocus)
            .setHandleAudioBecomingNoisy(handleFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                if (sharedAudioSessionId > 0) {
                    try {
                        audioSessionId = sharedAudioSessionId
                    } catch (_: Throwable) {
                    }
                }
            }
    }

    @OptIn(UnstableApi::class)
    private fun wrapDeckForSession(deck: ExoPlayer): ForwardingPlayer {
        val app = NoctaliaMusicApp.instance
        return object : ForwardingPlayer(deck) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() = app.playerController.skipNext()
            override fun seekToNextMediaItem() = app.playerController.skipNext()
            override fun seekToPrevious() = app.playerController.skipPrevious()
            override fun seekToPreviousMediaItem() = app.playerController.skipPrevious()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = NoctaliaMusicApp.instance

        val httpFactory = OkHttpDataSource.Factory(app.api.streamHttpClient)
            .setUserAgent(YTMusicApi.VISIONOS_UA)
        val baseDataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val resolvingDataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == BROOK_TRACK_SCHEME) {
                // 0. Archivo local del teléfono (MediaStore MP3/FLAC/M4A/OGG/WAV/OPUS)
                val localMediaId = extractBrookLocalMediaId(uri)
                if (localMediaId != null) {
                    val trackId = "loc_$localMediaId"
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, localMediaId)
                    app.playerController.onStreamQualityResolved(trackId, app.localAudio.resolveQualityLabel(trackId))
                    return@Factory dataSpec.buildUpon()
                        .setUri(contentUri)
                        .setCustomData(trackId)
                        .build()
                }

                val videoId = extractBrookTrackVideoId(uri)
                    ?: throw IOException("Invalid brook-track URI")

                // 1. Caché offline local (instantáneo, 0 datos)
                val localFile = app.offlineCache.getLocalFile(videoId)
                if (localFile != null) {
                    app.playerController.onStreamQualityResolved(videoId, "OFFLINE · OPUS")
                    return@Factory dataSpec.buildUpon()
                        .setUri(Uri.fromFile(localFile))
                        .setCustomData(videoId)
                        .build()
                }

                // 2. Resolver URL Opus/AAC
                val resolved = app.api.resolveStreamUrlBlocking(
                    videoId = videoId,
                    forceRefresh = false,
                    preferHls = false,
                ) ?: throw TrackResolveFailedException(videoId)

                if (!SecurityValidators.isSafeStreamUrl(resolved.url)) {
                    throw TrackResolveFailedException(videoId, SecurityException("Unsafe stream URL blocked"))
                }

                // 3. Si YouTube devolvió HLS solo-audio, solicitar cambio limpio a HlsMediaSource
                if (isHlsUrlOrMime(resolved.url, resolved.mimeType)) {
                    throw HlsStreamRequiredException(videoId, resolved.url, resolved.qualityLabel)
                }

                app.playerController.onStreamQualityResolved(videoId, resolved.qualityLabel)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(resolved.url))
                    .setCustomData(videoId)
                    .build()
            } else {
                dataSpec
            }
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Sesión DSP compartida entre ambos decks para que Equalizer, BassBoost y LoudnessEnhancer
        // procesen también la pista entrante durante el Crossfade sin saltos ni recreaciones al terminar.
        val sharedAudioSessionId = try {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.generateAudioSessionId()
                ?.takeIf { it > 0 && it != AudioManager.ERROR }
                ?: C.AUDIO_SESSION_ID_UNSET
        } catch (_: Throwable) {
            C.AUDIO_SESSION_ID_UNSET
        }

        val deckA = createDeck(resolvingDataSourceFactory, audioAttrs, handleFocus = true, sharedAudioSessionId = sharedAudioSessionId)
        val deckB = createDeck(resolvingDataSourceFactory, audioAttrs, handleFocus = false, sharedAudioSessionId = sharedAudioSessionId)
        activeDeck = deckA
        secondaryDeck = deckB

        val initialSkipSilence = app.audioEffects.state.value.skipSilenceEnabled
        deckA.skipSilenceEnabled = initialSkipSilence
        deckB.skipSilenceEnabled = initialSkipSilence
        serviceScope.launch {
            app.audioEffects.state
                .map { it.skipSilenceEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    try { activeDeck?.skipSilenceEnabled = enabled } catch (_: Throwable) {}
                    try { secondaryDeck?.skipSilenceEnabled = enabled } catch (_: Throwable) {}
                }
        }

        if (deckA.audioSessionId > 0) {
            app.audioEffects.attachToSession(deckA.audioSessionId)
        }
        val sessionIdListener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0 && activeDeck?.audioSessionId == audioSessionId) {
                    app.audioEffects.attachToSession(audioSessionId)
                }
            }
        }
        deckA.addListener(sessionIdListener)
        deckB.addListener(sessionIdListener)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val libraryCallback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                if (controller.packageName == packageName) {
                    return super.onConnect(session, controller)
                }
                if (!isTrustedController(controller)) {
                    return MediaSession.ConnectionResult.reject()
                }
                // Solo Android Auto, SystemUI, Bluetooth, Wear OS y Asistente: controles seguros + catálogo Brook
                val externalPlayerCommands = Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_PREPARE)
                    .add(Player.COMMAND_SET_MEDIA_ITEM)
                    .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .build()
                val externalSessionCommands = SessionCommands.Builder()
                    .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.commands)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(externalPlayerCommands)
                    .setAvailableSessionCommands(externalSessionCommands)
                    .build()
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                if (!isTrustedController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val rootItem = buildBrowsableItem(AA_ROOT, "Brook Music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (!isTrustedController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val lib = app.library.state.value
                val items: List<MediaItem> = when {
                    parentId == AA_ROOT -> listOf(
                        buildBrowsableItem(AA_CAT_FAVS, "Favoritas (${lib.favorites.size})", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                        buildBrowsableItem(AA_CAT_RECENT, "Recientes (${lib.recent.size})", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                        buildBrowsableItem(AA_CAT_PLAYLISTS, "Playlists (${lib.playlists.size})", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                        buildBrowsableItem(AA_CAT_LOCAL, "Archivos locales (${app.localAudio.state.value.tracks.size})", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    )
                    parentId == AA_CAT_FAVS -> lib.favorites.take(100).map { buildAndroidAutoPlayableItem("aa_fav:${it.id}", it) }
                    parentId == AA_CAT_RECENT -> lib.recent.take(50).map { buildAndroidAutoPlayableItem("aa_rec:${it.id}", it) }
                    parentId == AA_CAT_PLAYLISTS -> lib.playlists.entries.map { (name, tracks) ->
                        buildBrowsableItem("$AA_CAT_PL_PREFIX$name", "$name (${tracks.size})", MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    }
                    parentId.startsWith(AA_CAT_PL_PREFIX) -> {
                        val plName = parentId.removePrefix(AA_CAT_PL_PREFIX)
                        lib.playlists[plName].orEmpty().take(100).map {
                            buildAndroidAutoPlayableItem("aa_pl:$plName|${it.id}", it)
                        }
                    }
                    parentId == AA_CAT_LOCAL -> app.localAudio.state.value.tracks.take(150).map {
                        buildAndroidAutoPlayableItem("aa_loc:${it.id}", it)
                    }
                    else -> emptyList()
                }
                val safePage = page.coerceAtLeast(0)
                val safeSize = pageSize.coerceIn(1, 200)
                val from = (safePage * safeSize).coerceAtMost(items.size)
                val to = (from + safeSize).coerceAtMost(items.size)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
                )
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                if (!isTrustedController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val track = resolveAndroidAutoTrack(mediaId)
                return if (track != null) {
                    Futures.immediateFuture(LibraryResult.ofItem(buildAndroidAutoPlayableItem(mediaId, track), null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<Void>> {
                if (!isTrustedController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val future = SettableFuture.create<LibraryResult<Void>>()
                val cleanQ = query.trim().take(120)
                serviceScope.launch {
                    val found = if (cleanQ.isNotEmpty()) app.api.search(cleanQ, limit = 15, mode = "songs") else emptyList()
                    if (cleanQ.isNotEmpty()) putSearchCache(cleanQ, found)
                    session.notifySearchResultChanged(browser, query, found.size, params)
                    future.set(LibraryResult.ofVoid(params))
                }
                return future
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (!isTrustedController(browser)) {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                val list = getSearchCache(query.trim().take(120))
                val items = list.map { buildAndroidAutoPlayableItem("aa_sr:${it.id}", it) }
                val safePage = page.coerceAtLeast(0)
                val safeSize = pageSize.coerceIn(1, 100)
                val from = (safePage * safeSize).coerceAtMost(items.size)
                val to = (from + safeSize).coerceAtMost(items.size)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
                )
            }

            @OptIn(UnstableApi::class)
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                if (controller.packageName == packageName) {
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }
                if (!isTrustedController(controller)) {
                    return Futures.immediateFailedFuture(SecurityException("Untrusted media controller"))
                }
                // Android Auto / asistente de voz: resolver IDs internos de Brook y devolver MediaItem válido
                // para que Media3 lo aplique sobre el player sin borrar la reproducción.
                val pickedIdx = startIndex.coerceAtLeast(0).takeIf { it < mediaItems.size } ?: 0
                val firstId = mediaItems.getOrNull(pickedIdx)?.mediaId
                val resolvedItem = if (!firstId.isNullOrBlank()) {
                    app.playerController.prepareFromAndroidAuto(firstId, allSearchCachedTracks())
                } else {
                    null
                }
                return if (resolvedItem != null) {
                    Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(listOf(resolvedItem), 0, 0L)
                    )
                } else {
                    Futures.immediateFailedFuture(UnsupportedOperationException("Unknown Brook mediaId"))
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                if (controller.packageName != packageName) {
                    if (!isTrustedController(controller)) {
                        return Futures.immediateFuture(mutableListOf())
                    }
                    // Resolver únicamente IDs del catálogo interno de Brook (nunca URLs arbitrarias externas)
                    val resolved = mediaItems.mapNotNull { item ->
                        resolveAndroidAutoTrack(item.mediaId)?.let { track ->
                            app.playerController.buildMediaItemForTrack(track)
                        }
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
                val offlineRoot = File(filesDir, "offline_audio").canonicalPath
                val safeItems = mediaItems.mapNotNull { item ->
                    val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return@mapNotNull null
                    val uriStr = uri.toString()
                    val allowed = when {
                        extractBrookTrackVideoId(uri) != null -> true
                        extractBrookLocalMediaId(uri) != null -> true
                        SecurityValidators.isSafeStreamUrl(uriStr) -> true
                        uri.scheme == "file" && !uri.path.isNullOrBlank() -> try {
                            File(uri.path!!).canonicalPath.startsWith("$offlineRoot/")
                        } catch (_: Exception) {
                            false
                        }
                        else -> false
                    }
                    if (!allowed) {
                        null
                    } else {
                        val b = item.buildUpon().setUri(uri)
                        if (isHlsUrlOrMime(uriStr, item.localConfiguration?.mimeType)) {
                            b.setMimeType(MimeTypes.APPLICATION_M3U8)
                        }
                        b.build()
                    }
                }.toMutableList()
                return Futures.immediateFuture(safeItems)
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, wrapDeckForSession(deckA), libraryCallback)
            .setSessionActivity(openAppIntent)
            .build()

        // Vincular ambos decks para reproducción gapless + Crossfade real solapado
        app.playerController.attachServiceDecks(
            active = deckA,
            standby = deckB,
            onDeckSwapped = { newActive ->
                val oldActive = activeDeck
                if (oldActive !== newActive) {
                    secondaryDeck = oldActive
                    activeDeck = newActive
                    // Transferir AudioFocus y pausa al desconectar auriculares (becoming noisy) al nuevo deck activo
                    oldActive?.let { prev ->
                        try {
                            prev.setHandleAudioBecomingNoisy(false)
                            prev.setAudioAttributes(audioAttrs, false)
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        newActive.setAudioAttributes(audioAttrs, true)
                        newActive.setHandleAudioBecomingNoisy(true)
                    } catch (_: Throwable) {
                    }
                    try {
                        mediaLibrarySession?.player = wrapDeckForSession(newActive)
                    } catch (_: Throwable) {
                    }
                    if (newActive.audioSessionId > 0) {
                        app.audioEffects.attachToSession(newActive.audioSessionId)
                    }
                }
            },
        )
    }

    private fun buildBrowsableItem(mediaId: String, title: String, mediaType: Int): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(meta)
            .build()
    }

    private fun buildAndroidAutoPlayableItem(mediaId: String, track: Track): MediaItem {
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifEmpty { "Brook" })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (track.thumb.isNotBlank()) {
            metaBuilder.setArtworkUri(Uri.parse(track.thumb))
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    private fun resolveAndroidAutoTrack(mediaId: String): Track? {
        val app = NoctaliaMusicApp.instance
        val lib = app.library.state.value
        return when {
            mediaId.startsWith("aa_fav:") -> lib.favorites.firstOrNull { it.id == mediaId.removePrefix("aa_fav:") }
            mediaId.startsWith("aa_rec:") -> lib.recent.firstOrNull { it.id == mediaId.removePrefix("aa_rec:") }
            mediaId.startsWith("aa_pl:") -> {
                val payload = mediaId.removePrefix("aa_pl:")
                val plName = payload.substringBeforeLast("|")
                val tid = payload.substringAfterLast("|", "")
                lib.playlists[plName]?.firstOrNull { it.id == tid }
            }
            mediaId.startsWith("aa_loc:") -> app.localAudio.findById(mediaId.removePrefix("aa_loc:"))
            mediaId.startsWith("aa_sr:") -> {
                val tid = mediaId.removePrefix("aa_sr:")
                allSearchCachedTracks().firstOrNull { it.id == tid }
            }
            else -> null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keepFg = startInForegroundRequired || NoctaliaMusicApp.instance.playerController.isTransitioningNext
        try {
            super.onUpdateNotification(session, keepFg)
        } catch (_: Throwable) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = activeDeck
        val transitioning = NoctaliaMusicApp.instance.playerController.isTransitioningNext
        val keepAlive = p != null && (p.playWhenReady || p.isPlaying || transitioning) &&
            (p.mediaItemCount > 0 || transitioning)
        if (!keepAlive) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try { NoctaliaMusicApp.instance.playerController.detachServicePlayer(activeDeck) } catch (_: Exception) {}
        try { NoctaliaMusicApp.instance.audioEffects.release() } catch (_: Exception) {}
        mediaLibrarySession?.run {
            release()
            mediaLibrarySession = null
        }
        try { activeDeck?.release() } catch (_: Exception) {}
        try { secondaryDeck?.release() } catch (_: Exception) {}
        activeDeck = null
        secondaryDeck = null
        super.onDestroy()
    }

    companion object {
        const val BROOK_TRACK_SCHEME = "brook-track"
        const val BROOK_TRACK_HOST = "yt"
        const val BROOK_LOCAL_HOST = "local"

        private const val AA_ROOT = "BROOK_ROOT"
        private const val AA_CAT_FAVS = "CAT_FAVS"
        private const val AA_CAT_RECENT = "CAT_RECENT"
        private const val AA_CAT_PLAYLISTS = "CAT_PLAYLISTS"
        private const val AA_CAT_LOCAL = "CAT_LOCAL"
        private const val AA_CAT_PL_PREFIX = "CAT_PL:"
        private const val MAX_AA_SEARCH_CACHE = 10

        private val ALLOWED_EXTERNAL_MEDIA_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.android.systemui",
            "com.google.android.googlequicksearchbox",
            "com.android.bluetooth",
            "com.google.android.bluetooth",
            "com.google.android.wearable.app",
            "com.samsung.android.app.watchmanager",
            "com.android.car.media",
            "com.google.android.carassistant",
            "com.google.android.apps.automotive.templates.host",
        )

        fun buildBrookTrackUri(videoId: String): Uri =
            Uri.parse("$BROOK_TRACK_SCHEME://$BROOK_TRACK_HOST/$videoId")

        fun buildBrookLocalUri(mediaId: Long): Uri =
            Uri.parse("$BROOK_TRACK_SCHEME://$BROOK_LOCAL_HOST/$mediaId")

        fun extractBrookTrackVideoId(uri: Uri?): String? {
            if (uri == null || uri.scheme != BROOK_TRACK_SCHEME || uri.host != BROOK_TRACK_HOST) return null
            val seg = uri.lastPathSegment ?: return null
            return seg.takeIf { SecurityValidators.isValidVideoId(it) }
        }

        fun extractBrookLocalMediaId(uri: Uri?): Long? {
            if (uri == null || uri.scheme != BROOK_TRACK_SCHEME || uri.host != BROOK_LOCAL_HOST) return null
            val seg = uri.lastPathSegment ?: return null
            return seg.toLongOrNull()?.takeIf { it > 0L }
        }

        fun isHlsUrlOrMime(url: String, mimeType: String?): Boolean {
            return mimeType.equals("application/x-mpegURL", ignoreCase = true) ||
                mimeType.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                url.contains("/hls_", ignoreCase = true) ||
                url.contains("manifest.googlevideo.com", ignoreCase = true) ||
                url.endsWith(".m3u8", ignoreCase = true)
        }
    }
}
