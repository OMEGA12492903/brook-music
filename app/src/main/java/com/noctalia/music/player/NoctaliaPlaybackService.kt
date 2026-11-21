package com.noctalia.music.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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

/**
 * Servicio en primer plano (Media3 MediaSessionService) endurecido:
 * - Solo nuestra propia app (`com.noctalia.music`) puede inyectar URLs/MediaItems.
 * - Controladores externos (pantalla de bloqueo, SystemUI, Bluetooth, coche) solo reciben
 *   comandos seguros de reproducción (Play/Pause/Next/Prev/Seek/Metadata), bloqueando SET_MEDIA_ITEM.
 * - ExoPlayer usa `streamHttpClient` sin cookies y validando cada salto HTTPS de googlevideo.com.
 */
class NoctaliaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = NoctaliaMusicApp.instance

        val dataSourceFactory = OkHttpDataSource.Factory(app.api.streamHttpClient)
            .setUserAgent(YTMusicApi.IOS_UA)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer = player

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
                val safeItems = mediaItems.filter { item ->
                    val uriStr = item.localConfiguration?.uri?.toString()
                    SecurityValidators.isSafeStreamUrl(uriStr)
                }.toMutableList()
                return Futures.immediateFuture(safeItems)
            }
        }

        mediaSession = MediaSession.Builder(this, wrappedPlayer)
            .setSessionActivity(openAppIntent)
            .setCallback(securityCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = exoPlayer
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
