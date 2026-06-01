package com.ntzb.myradio.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.LocalContext
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ntzb.myradio.R
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.player.PlayerController
import com.ntzb.myradio.ui.MainActivity
import com.ntzb.myradio.util.LogoGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

val STATION_ID_PARAM = ActionParameters.Key<String>("station_id")

/** Simple light/dark palette so the widget themes itself without depending on GlanceTheme. */
private data class WColors(
    val bg: Color, val onBg: Color,
    val tile: Color, val header: Color, val onHeader: Color
)

private fun colorsFor(context: Context): WColors {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (dark) WColors(
        bg = Color(0xFF1C1B1F), onBg = Color(0xFFE6E1E5),
        tile = Color(0xFF2B2930), header = Color(0xFF332D41), onHeader = Color(0xFFE8DEF8)
    ) else WColors(
        bg = Color(0xFFFFFBFE), onBg = Color(0xFF1C1B1F),
        tile = Color(0xFFECE6F0), header = Color(0xFFE8DEF8), onHeader = Color(0xFF1D192B)
    )
}

class RadioWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val stations = StationRepository.loadStations(context)
        val liked = LikesRepository.snapshot(context)
        val shown = stations.filter { it.id in liked }.ifEmpty { stations }.take(30)
        val nowPlaying = PlaybackSnapshot.read(context)
        // Title from the catalog by current station id (reliable), not the metadata string (can lag).
        val currentName = stations.firstOrNull { it.id == nowPlaying.stationId }?.name
            ?: nowPlaying.title
        val c = colorsFor(context)
        // Glance can't load URLs into Image — pre-decode logos to bitmaps (cached, downscaled).
        // No logo → generate an avatar from the station name.
        val bitmaps = shown.associate {
            it.id to (loadLogoBitmap(context, it.logoUri) ?: LogoGenerator.generate(it.name))
        }

        provideContent {
            Column(
                GlanceModifier.fillMaxSize().background(ColorProvider(c.bg)).padding(8.dp)
            ) {
                Header(currentName, nowPlaying.song, nowPlaying.isPlaying, c)
                Spacer(GlanceModifier.size(6.dp))
                LazyVerticalGrid(gridCells = GridCells.Adaptive(72.dp)) {
                    items(shown, itemId = { it.id.hashCode().toLong() }) { station ->
                        StationTile(station, bitmaps.getValue(station.id), c)
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, song: String, isPlaying: Boolean, c: WColors) {
        val context = LocalContext.current
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        Row(
            GlanceModifier.fillMaxWidth().background(ColorProvider(c.header)).cornerRadius(12.dp).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tapping the header (station info area) opens the app.
            Column(GlanceModifier.defaultWeight().clickable(openApp)) {
                Text(
                    text = title.ifBlank { "Not playing" },
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(c.onHeader))
                )
                if (song.isNotBlank()) {
                    Text(text = song, maxLines = 1, style = TextStyle(color = ColorProvider(c.onHeader)))
                }
            }
            ControlIcon(R.drawable.ic_volume_down, "Volume down", actionRunCallback<VolumeDownAction>(), c)
            ControlIcon(R.drawable.ic_volume_up, "Volume up", actionRunCallback<VolumeUpAction>(), c)
            ControlIcon(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                "Play/Pause", actionRunCallback<TogglePlayAction>(), c
            )
            ControlIcon(R.drawable.ic_stop, "Stop", actionRunCallback<StopAction>(), c)
        }
    }

    @Composable
    private fun ControlIcon(resId: Int, desc: String, action: Action, c: WColors) {
        Box(
            GlanceModifier.size(40.dp).cornerRadius(20.dp).clickable(action),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(resId),
                contentDescription = desc,
                colorFilter = ColorFilter.tint(ColorProvider(c.onHeader)),
                modifier = GlanceModifier.size(24.dp)
            )
        }
    }

    @Composable
    private fun StationTile(station: Station, bitmap: Bitmap, c: WColors) {
        Box(
            GlanceModifier.padding(4.dp).clickable(
                actionRunCallback<PlayStationAction>(
                    actionParametersOf(STATION_ID_PARAM to station.id)
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = station.name,
                // Crop fills the square while keeping aspect ratio (no distortion).
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(64.dp).cornerRadius(12.dp)
            )
        }
    }
}

// Logos are static, but provideGlance reruns on every now-playing change. Cache decoded
// bitmaps process-wide so a song-title tick doesn't re-decode every liked station's logo.
private val logoCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

/** Loads a logo bitmap from a bundled asset or remote URL, downscaled and cached. */
private suspend fun loadLogoBitmap(context: Context, uri: String): Bitmap? {
    if (uri.isBlank()) return null
    logoCache[uri]?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when {
                uri.startsWith("file:///android_asset/") ->
                    context.assets.open(uri.removePrefix("file:///android_asset/")).use { it.readBytes() }
                uri.startsWith("http") -> {
                    val req = Request.Builder().url(uri).build()
                    OkHttpClient().newCall(req).execute().use { it.body?.bytes() }
                }
                else -> null
            } ?: return@runCatching null
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()?.also { logoCache[uri] = it }
    }
}

// --- Action callbacks ---------------------------------------------------------

class PlayStationAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val stationId = parameters[STATION_ID_PARAM] ?: return
        val station = StationRepository.loadStations(context).firstOrNull { it.id == stationId } ?: return
        PlayerController.playStation(context, station)
    }
}

class TogglePlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PlayerController.togglePlayPause(context)
    }
}

class StopAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PlayerController.stop(context)
    }
}

/** Adjust the system MEDIA stream volume (routes to Bluetooth when connected). */
private fun adjustVolume(context: Context, direction: Int) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    am.adjustStreamVolume(
        android.media.AudioManager.STREAM_MUSIC,
        direction,
        android.media.AudioManager.FLAG_SHOW_UI
    )
}

class VolumeUpAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        adjustVolume(context, android.media.AudioManager.ADJUST_RAISE)
    }
}

class VolumeDownAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        adjustVolume(context, android.media.AudioManager.ADJUST_LOWER)
    }
}
