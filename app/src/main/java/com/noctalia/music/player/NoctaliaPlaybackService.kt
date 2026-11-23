package com.noctalia.music.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.noctalia.music.MainActivity
import com.noctalia.music.NoctaliaMusicApp
import com.noctalia.music.data.SecurityValidators
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
 * Servicio en primer plano (Media3 MediaSessionService) endurecido con reproducción continua
 * sin cortes en segundo plano:
 * - Solo nuestra propia app (`com.noctalia.music`) puede inyectar URLs/MediaItems.
 * - `ResolvingDataSource` resuelve dinámicamente `brook-track://yt/<videoId>` en el hilo Loader
 *   de ExoPlayer mientras mantiene Foreground Service + `WAKE_MODE_NETWORK` sin pasar por `STATE_ENDED`.
 * - ExoPlayer usa `streamHttpClient` sin cookies y validando cada salto HTTPS de googlevideo.com.
 */
class NoctaliaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = NoctaliaMusicApp.instance

        val httpFactory = OkHttpDataSource.Factory(app.api.streamHttpClient)
            .setUserAgent(YTMusicApi.VISIONOS_UA)
        // DefaultDataSource permite reproducir tanto streams HTTPS validados como archivos locales de nuestra caché offline
        val baseDataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        // ResolvingDataSource permite encolar la siguiente canción directamente dentro de ExoPlayer:
        // al terminar una canción en segundo plano, ExoPlayer pasa directamente a la siguiente
        // sin entrar nunca en STATE_ENDED ni perder el servicio en primer plano ni la notificación.
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == BROOK_TRACK_SCHEME) {
                val videoId = extractBrookTrackVideoId(uri)
                    ?: throw IOException("Invalid brook-track videoId")

                // 1. Caché offline local (instantáneo, 0 datos)
                val localFile = app.offlineCache.getLocalFile(videoId)
                if (localFile != null) {
                    app.playerController.onStreamQualityResolved(videoId, "OFFLINE · OPUS")
                    return@Factory dataSpec.buildUpon()
                        .setUri(Uri.fromFile(localFile))
                        .setCustomData(videoId)
                        .build()
                }

                // 2. Resolver URL Opus/AAC (hit instantáneo si ya se precargó, o petición en segundo plano)
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

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingDataSourceFactory))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer = player
        player.skipSilenceEnabled = app.audioEffects.state.value.skipSilenceEnabled
        serviceScope.launch {
            app.audioEffects.state
                .map { it.skipSilenceEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    try {
                        player.skipSilenceEnabled = enabled
                    } catch (_: Throwable) {
                    }
                }
        }
        if (player.audioSessionId > 0) {
            app.audioEffects.attachToSession(player.audioSessionId)
        }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    app.audioEffects.attachToSession(audioSessionId)
                }
            }
        })

        val wrappedPlayer = object : ForwardingPlayer(player) {
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

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val securityCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                // Solo nuestra app tiene permisos completos (incluyendo cargar streams)
                if (controller.packageName == packageName) {
                    return super.onConnect(session, controller)
                }
                // Aplicaciones externas (SystemUI, Bluetooth, etc.): solo controles seguros de transporte
                val externalCommands = Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(externalCommands)
                    .build()
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                if (controller.packageName != packageName) {
                    return Futures.immediateFuture(mutableListOf())
                }
                val offlineRoot = File(filesDir, "offline_audio").canonicalPath
                val safeItems = mediaItems.mapNotNull { item ->
                    // Media3 IPC puede limpiar localConfiguration; recuperar desde requestMetadata.mediaUri
                    val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return@mapNotNull null
                    val uriStr = uri.toString()
                    val allowed = when {
                        extractBrookTrackVideoId(uri) != null -> true
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

        mediaSession = MediaSession.Builder(this, wrappedPlayer)
            .setSessionActivity(openAppIntent)
            .setCallback(securityCallback)
            .build()

        // Vincular el ExoPlayer DESPUÉS de construir MediaSession para que MediaSession
        // procese siempre las transiciones de Timeline antes que nuestro controlador.
        app.playerController.attachServicePlayer(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keepFg = startInForegroundRequired || NoctaliaMusicApp.instance.playerController.isTransitioningNext
        try {
            super.onUpdateNotification(session, keepFg)
        } catch (_: Throwable) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = exoPlayer
        val transitioning = NoctaliaMusicApp.instance.playerController.isTransitioningNext
        val keepAlive = p != null && (p.playWhenReady || p.isPlaying || transitioning) &&
            (p.mediaItemCount > 0 || transitioning)
        if (!keepAlive) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try { NoctaliaMusicApp.instance.playerController.detachServicePlayer(exoPlayer) } catch (_: Exception) {}
        try { NoctaliaMusicApp.instance.audioEffects.release() } catch (_: Exception) {}
        mediaSession?.run {
            release()
            mediaSession = null
        }
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val BROOK_TRACK_SCHEME = "brook-track"
        const val BROOK_TRACK_HOST = "yt"

        fun buildBrookTrackUri(videoId: String): Uri =
            Uri.parse("$BROOK_TRACK_SCHEME://$BROOK_TRACK_HOST/$videoId")

        fun extractBrookTrackVideoId(uri: Uri?): String? {
            if (uri == null || uri.scheme != BROOK_TRACK_SCHEME || uri.host != BROOK_TRACK_HOST) return null
            val seg = uri.lastPathSegment ?: return null
            return seg.takeIf { SecurityValidators.isValidVideoId(it) }
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
