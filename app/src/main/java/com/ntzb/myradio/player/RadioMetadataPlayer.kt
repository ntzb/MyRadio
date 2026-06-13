package com.ntzb.myradio.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
class RadioMetadataPlayer(
    player: Player,
    /** Invoked when next (true) / previous (false) is pressed — switches the liked station. */
    private val onSkip: (forward: Boolean) -> Unit
) : ForwardingPlayer(player) {

    @Volatile private var song: String = ""
    private val listeners = mutableListOf<Player.Listener>()

    // Advertise next/previous so the transport keys appear in Android Auto and the notification.
    // Live radio is a single MediaItem, so we override the seek-to-next/previous commands to mean
    // "switch to the next/previous liked station" instead of seeking within a (non-existent) queue.
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() = onSkip(true)
    override fun seekToNextMediaItem() = onSkip(true)
    override fun seekToPrevious() = onSkip(false)
    override fun seekToPreviousMediaItem() = onSkip(false)

    /** Called when the resolved song changes; refreshes metadata listeners (notification/UI). */
    fun setSong(song: String) {
        if (song == this.song) return
        this.song = song
        val md = mediaMetadata
        // MediaSession's player listener reacts to onMediaMetadataChanged and refreshes the
        // notification / connected controllers. (No synthetic onEvents — FlagSet isn't public API.)
        for (l in listeners.toList()) {
            l.onMediaMetadataChanged(md)
        }
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val station = base.station ?: base.title
        val hasSong = song.isNotBlank()
        val (artist, title) = splitSong(song)
        return base.buildUpon()
            // title/artist drive the media notification (station + full "artist - title").
            .apply { station?.let { setTitle(it) } }
            .apply { if (hasSong) setArtist(song) }
            // displayTitle/subtitle are what Android Auto & Bluetooth read, and the now-playing
            // card shows only two lines. So: line 1 = song title (or station when idle),
            // line 2 = "artist · station" — keeps all three (station, artist, title) visible.
            // (subtitle is ignored unless displayTitle is set, so displayTitle is always set.)
            .setDisplayTitle(if (hasSong && title.isNotBlank()) title else station)
            .apply {
                val sub = when {
                    !hasSong -> null
                    artist.isNotBlank() && station != null -> "$artist · $station"
                    artist.isNotBlank() -> artist
                    else -> station
                }
                setSubtitle(sub)
            }
            .build()
    }

    /** Splits a combined "Artist - Title" (as produced by NowPlayingResolver) back into the two. */
    private fun splitSong(s: String): Pair<String, String> {
        val i = s.indexOf(" - ")
        return if (i > 0) s.substring(0, i).trim() to s.substring(i + 3).trim() else "" to s.trim()
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
