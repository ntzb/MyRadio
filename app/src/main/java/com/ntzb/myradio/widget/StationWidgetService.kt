package com.ntzb.myradio.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ntzb.myradio.R
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.util.LogoGenerator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// Target for the SHORTER side of a logo. The bitmap keeps its aspect ratio; the ImageView's
// centerCrop fills the square tile. High enough to look crisp on dense screens.
private const val LOGO_PX = 256

/**
 * Backs the widget's liked-stations GridView. Using a RemoteViewsFactory (rather than the API-31
 * inline RemoteCollectionItems) is deliberate: it streams item RemoteViews lazily, so the many
 * station-logo bitmaps never get packed into one oversized parcel — the failure mode that the
 * docs warn about for bitmap-heavy collections.
 */
class StationWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        StationGridFactory(applicationContext)
}

class StationGridFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var stations: List<Station> = emptyList()
    private var playingId: String? = null

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val result = runCatching {
            runBlocking {
                val all = StationRepository.loadStations(context)
                val liked = LikesRepository.snapshot(context)
                val np = runCatching { PlaybackSnapshot.read(context) }.getOrNull()
                Pair(all.filter { it.id in liked }, np?.stationId)
            }
        }.getOrNull()
        stations = result?.first ?: emptyList()
        playingId = result?.second
    }

    override fun onDestroy() {
        stations = emptyList()
    }

    override fun getCount(): Int = stations.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tile)
        val station = stations.getOrNull(position) ?: return views
        views.setImageViewBitmap(R.id.tile_logo, logoFor(station))

        val isPlaying = station.id == playingId
        // Outline the station that's currently playing (box hugs the logo).
        views.setInt(
            R.id.tile_box, "setBackgroundResource",
            if (isPlaying) R.drawable.tile_playing_bg else R.drawable.tile_bg
        )

        val fillIn = Intent().putExtra(RadioWidgetProvider.EXTRA_STATION_ID, station.id)
        views.setOnClickFillInIntent(R.id.tile_root, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long =
        stations.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun logoFor(station: Station): Bitmap =
        runCatching { loadLogo(station.logoUri) }.getOrNull()
            ?: LogoGenerator.generate(station.name, LOGO_PX)

    private fun loadLogo(uri: String): Bitmap? {
        if (uri.isBlank()) return null
        cache[uri]?.let { return it }
        val bytes = when {
            uri.startsWith("file:///android_asset/") ->
                context.assets.open(uri.removePrefix("file:///android_asset/")).use { it.readBytes() }
            uri.startsWith("http") ->
                http.newCall(Request.Builder().url(uri).build()).execute()
                    .use { it.body?.bytes() }
            else -> null
        } ?: return null

        // Decode bounds first, pick a sample size near the target (keeps decode cheap).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (minOf(w, h) / (sample * 2) >= LOGO_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        // Downscale preserving aspect ratio so the SHORTER side == LOGO_PX. NOT forced square —
        // the ImageView's centerCrop trims the overflow, so logos crop instead of stretch.
        val minSide = minOf(decoded.width, decoded.height)
        val scaled = if (minSide > LOGO_PX) {
            val factor = LOGO_PX.toFloat() / minSide
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * factor).roundToInt().coerceAtLeast(1),
                (decoded.height * factor).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }
        return scaled.also { cache[uri] = it }
    }

    private companion object {
        val cache = ConcurrentHashMap<String, Bitmap>()
        // Short timeouts: a slow logo host must not stall the grid's binder thread — we fall back
        // to a generated avatar instead.
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
