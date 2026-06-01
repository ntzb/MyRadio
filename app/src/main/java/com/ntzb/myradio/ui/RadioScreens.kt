package com.ntzb.myradio.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.util.LogoGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(state: UiState, vm: RadioViewModel, onOpenNowPlaying: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Liked, 1 = All

    Scaffold(
        topBar = { TopAppBar(title = { Text("MyRadio") }) },
        bottomBar = {
            Column {
                if (state.current != null) NowPlayingStrip(state, vm, onOpenNowPlaying)
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                        label = { Text("Liked") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Radio, contentDescription = null) },
                        label = { Text("All") }
                    )
                }
            }
        }
    ) { padding ->
        val list = if (tab == 0) state.liked else state.stations
        if (tab == 0 && list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Tap the heart on a station to add it here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { st ->
                    StationRow(
                        station = st,
                        isCurrent = st.id == state.currentStationId,
                        isPlaying = state.isPlaying && st.id == state.currentStationId,
                        liked = st.id in state.likedIds,
                        onPlay = { vm.play(st) },
                        onLike = { vm.toggleLike(st.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    liked: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit
) {
    Surface(
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StationLogo(station.logoUri, station.name, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrent) {
                    Text(
                        if (isPlaying) "Playing" else "Paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onLike) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
    }
}

@Composable
private fun NowPlayingStrip(state: UiState, vm: RadioViewModel, onOpen: () -> Unit) {
    Surface(tonalElevation = 3.dp, onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StationLogo(state.current?.logoUri ?: "", state.current?.name ?: "", Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    state.stationName.ifBlank { state.current?.name ?: "" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = state.displaySong.ifBlank { if (state.isBuffering) "Buffering…" else "Live" }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { vm.togglePlayPause() }) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause"
                )
            }
            IconButton(onClick = { vm.stop() }) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(state: UiState, vm: RadioViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.stationName.ifBlank { state.current?.name ?: "" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StationLogo(
                state.current?.logoUri ?: "",
                state.current?.name ?: "",
                Modifier.size(220.dp).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(28.dp))
            Text(state.current?.name ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.displaySong.isNotBlank()) state.displaySong
                else if (state.isBuffering) "Buffering…" else "Live",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.width(24.dp))
                OutlinedIconButton(onClick = { vm.stop() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            }
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeDown, contentDescription = null)
                Slider(
                    value = state.volume,
                    onValueChange = { vm.setVolume(it) },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                Icon(Icons.Filled.VolumeUp, contentDescription = null)
            }
        }
    }
}

@Composable
fun StationLogo(uri: String, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    when {
        uri.startsWith("file:///android_asset/") -> {
            // Bundled logo — decode directly so it never depends on a fetcher's asset support.
            var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(uri) {
                bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val path = uri.removePrefix("file:///android_asset/")
                        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                    }.getOrNull()
                }
            }
            bitmap?.let {
                Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
            } ?: GeneratedLogo(name, modifier)
        }
        uri.startsWith("http") -> {
            SubcomposeAsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
                loading = { GeneratedLogo(name, Modifier.fillMaxSize()) },
                error = { GeneratedLogo(name, Modifier.fillMaxSize()) }
            )
        }
        else -> GeneratedLogo(name, modifier)
    }
}

/** Fallback avatar generated from the station name (matches the widget's generated logos). */
@Composable
private fun GeneratedLogo(name: String, modifier: Modifier = Modifier) {
    val bmp = remember(name) { LogoGenerator.generate(name).asImageBitmap() }
    Image(bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}
