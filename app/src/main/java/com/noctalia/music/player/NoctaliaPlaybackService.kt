package com.noctalia.music.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
import java.util.concurrent.ConcurrentHashMap

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
    private val androidAutoSearchCache = ConcurrentHashMap<String, List<Track>>()

    @OptIn(UnstableApi::class)
    private fun createDeck(
        resolvingFactory: ResolvingDataSource.Factory,
        audioAttrs: AudioAttributes,
        handleFocus: Boolean,
    ): ExoPlayer {
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(audioAttrs, handleFocus)
            .setHandleAudioBecomingNoisy(handleFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
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

        val deckA = createDeck(resolvingDataSourceFactory, audioAttrs, handleFocus = true)
        val deckB = createDeck(resolvingDataSourceFactory, audioAttrs, handleFocus = false)
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
                // Android Auto, SystemUI, Bluetooth, Wear OS: controles seguros + catálogo Brook
                val externalPlayerCommands = Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_PREPARE)
                    .add(Player.COMMAND_SET_MEDIA_ITEM)
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
                val future = SettableFuture.create<LibraryResult<Void>>()
                val cleanQ = query.trim().take(120)
                serviceScope.launch {
                    val found = if (cleanQ.isNotEmpty()) app.api.search(cleanQ, limit = 15, mode = "songs") else emptyList()
                    androidAutoSearchCache[cleanQ] = found
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
                val list = androidAutoSearchCache[query.trim().take(120)].orEmpty()
                val items = list.map { buildAndroidAutoPlayableItem("aa_sr:${it.id}", it) }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                if (controller.packageName != packageName) {
                    // Android Auto / asistente de voz: resolver solo IDs internos de Brook (0 URLs externas arbitrarias)
                    val firstId = mediaItems.firstOrNull()?.mediaId
                    if (!firstId.isNullOrBlank()) {
                        serviceScope.launch {
                            val searchPool = androidAutoSearchCache.values.flatten()
                            app.playerController.playFromAndroidAuto(firstId, searchPool)
                        }
                    }
                    return Futures.immediateFuture(mutableListOf())
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
                val plName = payload.substringBefore("|")
                val tid = payload.substringAfter("|", "")
                lib.playlists[plName]?.firstOrNull { it.id == tid }
            }
            mediaId.startsWith("aa_loc:") -> app.localAudio.findById(mediaId.removePrefix("aa_loc:"))
            mediaId.startsWith("aa_sr:") -> {
                val tid = mediaId.removePrefix("aa_sr:")
                androidAutoSearchCache.values.flatten().firstOrNull { it.id == tid }
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
