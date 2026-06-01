package com.ntzb.myradio.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.FlagSet
import androidx.media3.common.util.UnstableApi

/**
 * Wraps the ExoPlayer so the media notification (and any MediaController) shows the now-playing
 * song. Live radio never swaps the MediaItem, so ExoPlayer's metadata stays the station name —
 * there's no MediaItem change to carry ICY/polled song info. We inject the song into the metadata
 * and fire a metadata-changed event ourselves, without touching playback (no re-buffer).
 *
 * The title is pinned to the station name and the song goes into `artist`, so the notification
 * reads "<station>" / "<song>". The in-app UI derives its name from the title and its song from
 * PlaybackSnapshot, so it's unaffected.
 */
@OptIn(UnstableApi::class)
class RadioMetadataPlayer(player: Player) : ForwardingPlayer(player) {

    @Volatile private var song: String = ""
    private val listeners = mutableListOf<Player.Listener>()

    /** Called when the resolved song changes; refreshes metadata listeners (notification/UI). */
    fun setSong(song: String) {
        if (song == this.song) return
        this.song = song
        val md = mediaMetadata
        val events = Player.Events(
            FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build()
        )
        for (l in listeners.toList()) {
            l.onMediaMetadataChanged(md)
            l.onEvents(this, events)
        }
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val station = base.station ?: base.title
        return base.buildUpon()
            .apply { station?.let { setTitle(it) } }   // keep the station as the title
            .apply { if (song.isNotBlank()) setArtist(song) }
            .build()
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }
}
