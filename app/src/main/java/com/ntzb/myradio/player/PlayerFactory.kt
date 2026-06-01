package com.ntzb.myradio.player

import android.content.Context
import android.net.Uri
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
import com.ntzb.myradio.model.Station
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
object PlayerFactory {

    fun buildExoPlayer(context: Context): ExoPlayer {
        val http = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val dataSource = OkHttpDataSource.Factory(http).setUserAgent(Constants.USER_AGENT)
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
     * Builds a MediaItem with the correct MIME so ExoPlayer picks the right source.
     * Critical: Kan uses `.livx` (DASH) which has no recognized extension, so we must
     * declare APPLICATION_MPD explicitly or it would wrongly fall through to progressive.
     */
    fun buildMediaItem(station: Station, url: String): MediaItem {
        val mime = when {
            station.module == "kan" || url.endsWith(".livx") || url.contains(".mpd") ->
                MimeTypes.APPLICATION_MPD
            url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        val artwork = station.logoUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
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
