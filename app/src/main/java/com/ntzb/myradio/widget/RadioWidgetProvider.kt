package com.ntzb.myradio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.player.PlayerController
import com.ntzb.myradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget. Renders the liked-stations grid + now-playing header, and turns header /
 * tile taps into playback commands. Updates are pushed directly via [WidgetUpdater] (no Glance).
 */
class RadioWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // System-triggered (placement, reboot): do a full rebuild.
        runAsync(context) { WidgetUpdater.pushAll(context) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        // Widget resized → rebuild so the header text re-scales to the new width.
        runAsync(context) { WidgetUpdater.pushAll(context) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
                runAsync(context) {
                    if (!stationId.isNullOrBlank()) {
                        val station = runCatching { StationRepository.loadStations(context) }
                            .getOrNull()?.firstOrNull { it.id == stationId }
                        if (station != null) PlayerController.playStation(context, station)
                    }
                    WidgetUpdater.pushHeader(context)
                }
            }
            ACTION_TOGGLE -> runAsync(context) {
                PlayerController.togglePlayPause(context); WidgetUpdater.pushHeader(context)
            }
            ACTION_STOP -> runAsync(context) {
                PlayerController.stop(context); WidgetUpdater.pushAll(context) // clears the outline too
            }
            ACTION_VOL_UP -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            ACTION_VOL_DOWN -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            else -> super.onReceive(context, intent)
        }
    }

    private fun adjustVolume(context: Context, direction: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Adjust silently (no FLAG_SHOW_UI). The system volume panel is an overlay that the next
        // tap dismisses, which made the panel flicker show/hide on alternating taps. STREAM_MUSIC
        // still follows BT/speaker routing; each tap reliably steps the volume.
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    /** Run suspend work from a broadcast, keeping the process alive until it finishes. */
    private fun runAsync(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try { block() } catch (_: Throwable) {} finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_PLAY = "com.ntzb.myradio.widget.ACTION_PLAY"
        const val ACTION_TOGGLE = "com.ntzb.myradio.widget.ACTION_TOGGLE"
        const val ACTION_STOP = "com.ntzb.myradio.widget.ACTION_STOP"
        const val ACTION_VOL_UP = "com.ntzb.myradio.widget.ACTION_VOL_UP"
        const val ACTION_VOL_DOWN = "com.ntzb.myradio.widget.ACTION_VOL_DOWN"
        const val EXTRA_STATION_ID = "station_id"

        private fun self(context: Context) = ComponentName(context, RadioWidgetProvider::class.java)

        fun broadcast(context: Context, action: String): PendingIntent {
            val intent = Intent(action).setComponent(self(context))
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Mutable template: each grid item fills in its station id via setOnClickFillInIntent. */
        fun playTemplate(context: Context): PendingIntent {
            val intent = Intent(ACTION_PLAY).setComponent(self(context))
            return PendingIntent.getBroadcast(
                context, ACTION_PLAY.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
