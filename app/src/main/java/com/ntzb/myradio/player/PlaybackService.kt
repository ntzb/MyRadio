package com.ntzb.myradio.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ntzb.myradio.data.NowPlaying
import com.ntzb.myradio.data.NowPlayingResolver
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.ui.MainActivity
import com.ntzb.myradio.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the single ExoPlayer + MediaLibrarySession. It is a MediaLibraryService (not just a
 * MediaSessionService) so Android Auto can browse the station list and start playback; the in-app
 * UI and widget drive it through MediaController (PlayerController). This service owns playback,
 * the media notification, ICY metadata, the fallback-to-next-URL logic, the indefinite reconnect
 * retry on network loss, and mirrors state into PlaybackSnapshot.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var exo: ExoPlayer? = null                      // real player; our listener attaches here
    private var metadataPlayer: RadioMetadataPlayer? = null // wraps exo, injects song for notification
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var icySong: String = ""     // from ICY (Icecast streams)
    @Volatile private var polledSong: String = ""  // from Kan ACRCloud API (DASH streams)
    @Volatile private var lastWidgetStationId: String? = null  // detect station changes for the widget
    private var pollJob: Job? = null

    // Indefinite reconnect retry (network loss): exponential backoff, capped.
    @Volatile private var retryDelayMs = RETRY_MIN_MS
    private var retryJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                when (p.playbackState) {
                    // Recovered / playing fine → stop retrying and reset the backoff.
                    Player.STATE_READY -> { retryJob?.cancel(); retryJob = null; retryDelayMs = RETRY_MIN_MS }
                    // Stopped/cleared by the user → abandon any pending retry.
                    Player.STATE_IDLE -> if (p.mediaItemCount == 0) { retryJob?.cancel(); retryJob = null }
                }
            }
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
            exo?.let { publish(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = exo ?: return
            if (player.mediaItemCount == 0) return   // user stopped — nothing to recover
            // 1) Try this station's next hardcoded fallback URL immediately.
            val next = PlaybackFallback.nextItem()
            if (next != null) {
                player.setMediaItem(next); player.prepare(); player.play()
                return
            }
            // 2) All URLs failed (most likely the network dropped) — retry forever with backoff.
            scheduleRetry()
        }
    }

    /** Re-prepare the primary URL after a backoff delay; loops via repeated onPlayerError. */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val target = PlaybackFallback.stationId() ?: return
        retryJob = scope.launch {
            val wait = retryDelayMs
            retryDelayMs = (wait * 2).coerceAtMost(RETRY_MAX_MS)
            delay(wait)
            val player = exo ?: return@launch
            // Bail if the user switched stations or stopped while we were waiting.
            if (player.mediaItemCount == 0 || PlaybackFallback.stationId() != target) return@launch
            val first = PlaybackFallback.firstItem() ?: return@launch
            player.setMediaItem(first); player.prepare(); player.play()
        }
    }

    // --- Android Auto / browser content tree ---------------------------------------------------

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                // We return the whole (small) station list in one page; later pages are empty so a
                // paginating browser doesn't duplicate entries.
                val items = if (parentId == ROOT_ID && page == 0) {
                    ImmutableList.copyOf(loadStations().map(::stationToBrowsableItem))
                } else {
                    ImmutableList.of()
                }
                future.set(LibraryResult.ofItemList(items, params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val item = if (mediaId == ROOT_ID) rootItem()
                    else loadStations().firstOrNull { it.id == mediaId }?.let(::stationToBrowsableItem)
                future.set(
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            return future
        }

        /**
         * The single resolution point for every play (app, widget, Android Auto). Media3 strips the
         * URI on the controller→session IPC, so controllers hand us items carrying only a mediaId;
         * we resolve each to a playable URL via PlayerController.prepareStation (which also sets the
         * fallback list, snapshot, widget, and currentStationId so skip/now-playing stay correct).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val stations = loadStations()
                // Return a SAME-SIZE list (Media3 seeks to the requested index in it; a shorter list
                // would risk an out-of-bounds seek). Unresolvable items keep their original (URI-less)
                // entry, which the player rejects via onPlayerError rather than crashing.
                val resolved = mediaItems.map { req ->
                    if (req.localConfiguration != null) return@map req   // already playable (defensive)
                    val st = stations.firstOrNull { it.id == req.mediaId }
                    val item = st?.let { PlayerController.prepareStation(this@PlaybackService, it) }
                    item ?: req
                }.toMutableList()
                future.set(resolved)
            }
            return future
        }
    }

    private suspend fun loadStations(): List<Station> =
        runCatching { StationRepository.loadStations(this) }.getOrDefault(emptyList())

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("MyRadio")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun stationToBrowsableItem(st: Station): MediaItem = MediaItem.Builder()
        .setMediaId(st.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(st.name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                // Only http art loads in Auto's process (asset:// can't); skip otherwise.
                .apply {
                    st.logoUri.takeIf { it.startsWith("http") }?.let { setArtworkUri(Uri.parse(it)) }
                }
                .build()
        )
        .build()

    // --- lifecycle -----------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = PlayerFactory.buildExoPlayer(this)
        exoPlayer.addListener(listener)
        val fwd = RadioMetadataPlayer(exoPlayer) { forward ->
            scope.launch { PlayerController.skipToLiked(this@PlaybackService, forward) }
        }
        exo = exoPlayer
        metadataPlayer = fwd
        // Tapping the media notification opens the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        // The session uses the forwarding player so notification/controllers/Auto see the song.
        session = MediaLibrarySession.Builder(this, fwd, libraryCallback)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        exo?.removeListener(listener)
        session?.release()
        metadataPlayer?.release()   // ForwardingPlayer.release() releases the wrapped ExoPlayer too
        session = null
        exo = null
        metadataPlayer = null
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
                    exo?.let { publish(it) }
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
        // Inject the song into the notification/controller metadata (no re-buffer).
        metadataPlayer?.setSong(song)
        scope.launch {
            PlaybackSnapshot.write(
                this@PlaybackService,
                NowPlaying(stationId, stationName, song, p.isPlaying)
            )
            // Station change → full rebuild (moves the "playing" outline); otherwise just the header.
            if (stationId != lastWidgetStationId) {
                lastWidgetStationId = stationId
                WidgetUpdater.pushAll(this@PlaybackService)
            } else {
                WidgetUpdater.pushHeader(this@PlaybackService)
            }
        }
    }

    private companion object {
        const val ROOT_ID = "root"
        const val RETRY_MIN_MS = 2_000L
        const val RETRY_MAX_MS = 30_000L
    }
}
