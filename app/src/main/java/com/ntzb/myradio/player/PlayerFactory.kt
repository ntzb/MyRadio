package com.ntzb.myradio.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ntzb.myradio.data.LogoProvider
import com.ntzb.myradio.model.Station
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
object PlayerFactory {

    fun buildExoPlayer(context: Context): ExoPlayer {
        val http = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val dataSource = OkHttpDataSource.Factory(http)
            .setUserAgent(Constants.USER_AGENT)
            // Ask Icecast/Shoutcast servers to send in-stream ICY metadata (now-playing title).
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource) // auto: dash/hls/progressive

        // Tuned for LIVE radio: ~2.5s start, resilient rebuffer, time- (not size-) driven.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)      // keep streaming with screen off
            .setHandleAudioBecomingNoisy(true)     // pause when headphones unplugged
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
    }

    /**
     * A lightweight MediaItem carrying only the station id + metadata, NO URI. The controller hands
     * this to the session; the session's onAddMediaItems resolves it to a playable URL. This is
     * required because Media3 strips the URI (localConfiguration) on the controller→session IPC, so
     * resolving must happen session-side — doing it once here avoids a wasteful double resolve.
     */
    fun buildRequestItem(station: Station): MediaItem {
        // Artwork is served through our content:// provider (Auto can't read bundled assets).
        val artwork = station.logoUri.takeIf { it.isNotBlank() }?.let { LogoProvider.uriFor(station.id) }
        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setStation(station.name)
            .apply { artwork?.let { setArtworkUri(it) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(station.id)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Builds a fully playable MediaItem (with URI + correct MIME) so ExoPlayer picks the right source.
     * Critical: Kan uses `.livx` (DASH) which has no recognized extension, so we must
     * declare APPLICATION_MPD explicitly or it would wrongly fall through to progressive.
     */
    fun buildMediaItem(station: Station, url: String): MediaItem {
        // MIME is derived from the URL, never the module: a Kan station's fallback can be a
        // plain .mp3 (e.g. the StreamTheWorld mirror), which must NOT be treated as DASH.
        // Kan's primary .livx is DASH; dvr is already stripped so it ends with ".livx".
        val mime = when {
            url.endsWith(".livx") || url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        val artwork = station.logoUri.takeIf { it.isNotBlank() }?.let { LogoProvider.uriFor(station.id) }
        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setStation(station.name)
            .apply { artwork?.let { setArtworkUri(it) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(url)
            .setMediaMetadata(metadata)
            .apply { mime?.let { setMimeType(it) } }
            .build()
    }
}
