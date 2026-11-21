package com.noctalia.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.noctalia.music.MainActivity
import com.noctalia.music.NoctaliaMusicApp
import com.noctalia.music.R

/**
 * Receptor NO exportado (`android:exported="false"`) para acciones de botones del widget.
 * Impide que apps externas inyecten broadcasts falsos a Brook.
 */
class BrookWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = try { NoctaliaMusicApp.instance } catch (_: Throwable) { null } ?: return
        when (intent.action) {
            BrookWidgetProvider.ACTION_PLAY_PAUSE -> app.playerController.togglePlayPause()
            BrookWidgetProvider.ACTION_NEXT -> app.playerController.skipNext()
            BrookWidgetProvider.ACTION_PREV -> app.playerController.skipPrevious()
        }
    }
}

class BrookWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.noctalia.music.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT = "com.noctalia.music.WIDGET_NEXT"
        const val ACTION_PREV = "com.noctalia.music.WIDGET_PREV"

        fun updateAllWidgets(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val comp = ComponentName(context, BrookWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(comp)
                if (ids.isEmpty()) return

                val st = try { NoctaliaMusicApp.instance.playerController.state.value } catch (_: Throwable) { null }
                val title = st?.currentTrack?.title ?: "Brook Music"
                val artist = st?.currentTrack?.artist ?: "Toca para buscar música"
                val badge = if (st?.currentTrack != null) "BROOK · ${st.qualityLabel}" else "BROOK · OPUS"
                val playGlyph = if (st?.isPlaying == true) "⏸" else "▶"

                val views = RemoteViews(context.packageName, R.layout.widget_brook).apply {
                    setTextViewText(R.id.widget_badge, badge)
                    setTextViewText(R.id.widget_title, title)
                    setTextViewText(R.id.widget_artist, artist)
                    setTextViewText(R.id.widget_btn_play, playGlyph)

                    val openApp = PendingIntent.getActivity(
                        context,
                        10,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    setOnClickPendingIntent(R.id.widget_root, openApp)
                    setOnClickPendingIntent(R.id.widget_btn_play, actionPending(context, ACTION_PLAY_PAUSE, 11))
                    setOnClickPendingIntent(R.id.widget_btn_next, actionPending(context, ACTION_NEXT, 12))
                    setOnClickPendingIntent(R.id.widget_btn_prev, actionPending(context, ACTION_PREV, 13))
                }
                mgr.updateAppWidget(ids, views)
            } catch (_: Throwable) {
            }
        }

        private fun actionPending(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, BrookWidgetActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
