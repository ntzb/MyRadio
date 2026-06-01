package com.ntzb.myradio.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.GlanceTheme
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ntzb.myradio.R
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

val STATION_ID_PARAM = ActionParameters.Key<String>("station_id")

class RadioWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val stations = StationRepository.loadStations(context)
        val liked = LikesRepository.snapshot(context)
        val shown = stations.filter { it.id in liked }.ifEmpty { stations }.take(30)
        val nowPlaying = PlaybackSnapshot.read(context)
        // Glance can't load URLs into Image — pre-decode logos to bitmaps (downscaled).
        val bitmaps = shown.associate { it.id to loadLogoBitmap(context, it.logoUri) }

        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .padding(8.dp)
                ) {
                    Header(nowPlaying.title, nowPlaying.song, nowPlaying.isPlaying)
                    Spacer(GlanceModifier.size(6.dp))
                    LazyVerticalGrid(gridCells = GridCells.Adaptive(72.dp)) {
                        items(shown, itemId = { it.id.hashCode().toLong() }) { station ->
                            StationTile(station, bitmaps[station.id])
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, song: String, isPlaying: Boolean) {
        Row(
            GlanceModifier.fillMaxWidth()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(12.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    text = title.ifBlank { "Not playing" },
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer)
                )
                if (song.isNotBlank()) {
                    Text(
                        text = song,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer)
                    )
                }
            }
            ControlIcon(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                "Play/Pause",
                actionRunCallback<TogglePlayAction>()
            )
            Spacer(GlanceModifier.width(4.dp))
            ControlIcon(R.drawable.ic_stop, "Stop", actionRunCallback<StopAction>())
        }
    }

    @Composable
    private fun ControlIcon(resId: Int, desc: String, action: Action) {
        Box(
            GlanceModifier.size(40.dp).cornerRadius(20.dp).clickable(action),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(resId),
                contentDescription = desc,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(24.dp)
            )
        }
    }

    @Composable
    private fun StationTile(station: Station, bitmap: Bitmap?) {
        Box(GlanceModifier.padding(4.dp), contentAlignment = Alignment.Center) {
            Box(
                GlanceModifier.size(64.dp)
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(12.dp)
                    .clickable(
                        actionRunCallback<PlayStationAction>(
                            actionParametersOf(STATION_ID_PARAM to station.id)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = station.name,
                        modifier = GlanceModifier.size(58.dp).cornerRadius(10.dp)
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_radio),
                        contentDescription = station.name,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/** Loads a logo bitmap from a bundled asset or remote URL, downscaled for widget memory limits. */
private suspend fun loadLogoBitmap(context: Context, uri: String): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when {
                uri.isBlank() -> null
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
        }.getOrNull()
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
