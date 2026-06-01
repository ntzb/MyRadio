package com.ntzb.myradio.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.RemoteViews
import com.ntzb.myradio.R
import com.ntzb.myradio.data.NowPlaying
import com.ntzb.myradio.data.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pushes widget updates directly through [AppWidgetManager] — bypassing Glance and its 45–50 s
 * session lock. Two paths:
 *  - [pushAll]: full rebuild (header + grid). Use when the liked set or the playing station changes.
 *  - [pushHeader]: cheap partial update of just the now-playing header (song / play-state). Safe to
 *    call at high frequency from the foreground playback service — no grid rebuild, no size blow-up.
 *
 * Both are suspend + run off the main thread. Callers are already inside coroutines (the player,
 * the view model, the worker) or use goAsync() (the provider's broadcast handlers).
 */
object WidgetUpdater {

    private fun widgetIds(context: Context, mgr: AppWidgetManager): IntArray =
        mgr.getAppWidgetIds(ComponentName(context, RadioWidgetProvider::class.java))

    suspend fun pushAll(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = widgetIds(ctx, mgr)
        if (ids.isEmpty()) return@withContext
        val np = runCatching { PlaybackSnapshot.read(ctx) }.getOrNull()
        for (id in ids) {
            runCatching { mgr.updateAppWidget(id, buildRoot(ctx, id, np)) }
        }
        // Tell each grid's factory to reload (liked set / playing-station outline may have changed).
        for (id in ids) runCatching { mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_grid) }
    }

    suspend fun pushHeader(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = widgetIds(ctx, mgr)
        if (ids.isEmpty()) return@withContext
        val np = runCatching { PlaybackSnapshot.read(ctx) }.getOrNull()
        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_radio)
            applyHeader(ctx, views, np)
            runCatching { mgr.partiallyUpdateAppWidget(id, views) }
        }
    }

    /** Builds the full widget: header + grid adapter + click wiring. */
    fun buildRoot(context: Context, appWidgetId: Int, np: NowPlaying?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_radio)
        applyHeader(context, views, np)

        // Scale the header text with the widget's width so it isn't tiny on a large (tablet) widget.
        val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val minWidthDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val titleSp = (if (minWidthDp > 0) minWidthDp / 16f else 16f).coerceIn(16f, 30f)
        views.setTextViewTextSize(R.id.np_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
        views.setTextViewTextSize(R.id.np_song, TypedValue.COMPLEX_UNIT_SP, (titleSp - 3f))

        // Unique intent per widget id so the launcher keeps multiple instances distinct.
        val adapterIntent = Intent(context, StationWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.fromParts("myradio", appWidgetId.toString(), null)
        }
        views.setRemoteAdapter(R.id.widget_grid, adapterIntent)
        views.setEmptyView(R.id.widget_grid, R.id.widget_empty)
        // Tile taps are delivered through this template + each item's fillInIntent (station id).
        views.setPendingIntentTemplate(R.id.widget_grid, RadioWidgetProvider.playTemplate(context))
        return views
    }

    private fun applyHeader(context: Context, views: RemoteViews, np: NowPlaying?) {
        val playing = np?.isPlaying == true
        val title = np?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
        val song = when {
            !np?.song.isNullOrBlank() -> np!!.song
            playing -> ""
            else -> context.getString(R.string.not_playing)
        }
        views.setTextViewText(R.id.np_title, title)
        views.setTextViewText(R.id.np_song, song)
        views.setImageViewResource(
            R.id.btn_toggle,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(R.id.np_text, RadioWidgetProvider.openApp(context))
        views.setOnClickPendingIntent(R.id.btn_toggle, RadioWidgetProvider.broadcast(context, RadioWidgetProvider.ACTION_TOGGLE))
        views.setOnClickPendingIntent(R.id.btn_stop, RadioWidgetProvider.broadcast(context, RadioWidgetProvider.ACTION_STOP))
        views.setOnClickPendingIntent(R.id.btn_vol_up, RadioWidgetProvider.broadcast(context, RadioWidgetProvider.ACTION_VOL_UP))
        views.setOnClickPendingIntent(R.id.btn_vol_down, RadioWidgetProvider.broadcast(context, RadioWidgetProvider.ACTION_VOL_DOWN))
    }
}
