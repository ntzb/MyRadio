package com.ntzb.myradio.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.glance.appwidget.updateAll
import com.ntzb.myradio.ui.MainActivity
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ntzb.myradio.data.NowPlaying
import com.ntzb.myradio.data.NowPlayingResolver
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.widget.RadioWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the single ExoPlayer + MediaSession. UI and widget control it through MediaController
 * (PlayerController). This service owns playback, the media notification, ICY metadata, the
 * fallback-to-next-URL logic on error, and mirrors state into PlaybackSnapshot + the widget.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: Player? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var icySong: String = ""     // from ICY (Icecast streams)
    @Volatile private var polledSong: String = ""  // from Kan ACRCloud API (DASH streams)
    private var pollJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED
                )
            ) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    icySong = ""
                    polledSong = ""
                    restartNowPlayingPolling(p.currentMediaItem?.mediaId)
                }
                publish(p)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                (metadata.get(i) as? IcyInfo)?.title?.let { title ->
                    if (title.isNotBlank()) icySong = title
                }
            }
            player?.let { publish(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Try the next fallback URL for this station, if any.
            val next = PlaybackFallback.nextItem() ?: return
            player?.apply {
                setMediaItem(next)
                prepare()
                play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exo = PlayerFactory.buildExoPlayer(this)
        exo.addListener(listener)
        player = exo
        // Tapping the media notification opens the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, exo).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.removeListener(listener)
            player.release()
            release()
        }
        session = null
        player = null
        scope.cancel()
        super.onDestroy()
    }

    /** Poll the station's now-playing feed (Kan ACRCloud / glz Dalet XML) while it's playing. */
    private fun restartNowPlayingPolling(stationId: String?) {
        pollJob?.cancel()
        pollJob = null
        if (stationId == null || !NowPlayingResolver.hasSource(stationId)) return
        pollJob = scope.launch {
            while (isActive) {
                val s = NowPlayingResolver.fetch(stationId).orEmpty()
                if (s != polledSong) {
                    polledSong = s
                    player?.let { publish(it) }
                }
                delay(20_000)
            }
        }
    }

    private fun publish(p: Player) {
        val md = p.mediaMetadata
        val stationId = p.currentMediaItem?.mediaId
        val stationName = md.station?.toString() ?: md.title?.toString().orEmpty()
        val mdSong = md.title?.toString()?.takeIf { it.isNotBlank() && it != stationName }.orEmpty()
        val song = mdSong.ifBlank { icySong }.ifBlank { polledSong }
        scope.launch {
            PlaybackSnapshot.write(
                this@PlaybackService,
                NowPlaying(stationId, stationName, song, p.isPlaying)
            )
            RadioWidget().updateAll(this@PlaybackService)
        }
    }
}
