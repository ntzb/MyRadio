package com.ntzb.myradio.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.NowPlaying
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.data.StreamResolver
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-wide single MediaController used by the UI, so commands drive the same session
 * without controller-release races. The controller lives for the process.
 */
object PlayerController {

    private val mutex = Mutex()
    private val skipMutex = Mutex()                       // serializes next/previous presses
    @Volatile private var controller: MediaController? = null
    // The intended current station, updated synchronously on play/stop. Used by skipToLiked so
    // rapid presses advance step-by-step (the MediaController's currentMediaItem lags behind).
    @Volatile private var currentStationId: String? = null

    // MediaController must only be touched on the main thread (it throws otherwise),
    // so the whole get() runs on Main.
    suspend fun get(context: Context): MediaController = withContext(Dispatchers.Main) {
        controller?.let { if (it.isConnected) return@withContext it }
        mutex.withLock {
            controller?.let { if (it.isConnected) return@withContext it }
            connectOnMain(context.applicationContext).also { controller = it }
        }
    }

    /** Caller must already be on the main thread. */
    private suspend fun connectOnMain(context: Context): MediaController =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    try { cont.resume(future.get()) }
                    catch (e: Exception) { cont.resumeWithException(e) }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    suspend fun playStation(context: Context, station: Station) {
        // Write the snapshot FIRST (instant) so the now-playing strip reflects the new station
        // immediately, before the possibly-slow stream resolve/connect.
        PlaybackSnapshot.write(context, NowPlaying(station.id, station.name, "", true))
        WidgetUpdater.pushHeader(context)   // instant station name in the widget header

        val candidates = StreamResolver.resolveCandidates(station)   // resolves on IO
        val firstUrl = candidates.firstOrNull() ?: return            // nothing resolvable → bail
        PlaybackFallback.set(station, candidates)
        val item = PlayerFactory.buildMediaItem(station, firstUrl)
        val c = get(context)
        withContext(Dispatchers.Main) {
            // Set the "current" only when we actually commit to playing — ordered with setMediaItem
            // on Main, so a racing list-tap and a skip can't leave currentStationId out of sync.
            currentStationId = station.id
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    suspend fun togglePlayPause(context: Context) {
        val c = get(context)
        withContext(Dispatchers.Main) {
            if (c.isPlaying) {
                c.pause()
            } else {
                // After stop() the player is IDLE and needs re-preparing before it can play.
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    suspend fun stop(context: Context) {
        currentStationId = null
        val c = get(context)
        // Stop AND clear the item so the now-playing strip resets to "Not playing".
        withContext(Dispatchers.Main) {
            c.stop()
            c.clearMediaItems()
        }
    }

    /**
     * Skip to the next/previous LIKED station, in catalog order (same order the app/widget show),
     * wrapping around. Drives the next/previous transport keys in Android Auto, the notification,
     * and the app. If nothing liked, does nothing; if the current station isn't liked, jumps to the
     * first (forward) or last (backward) liked station.
     */
    suspend fun skipToLiked(context: Context, forward: Boolean) = skipMutex.withLock {
        val liked = StationRepository.loadStations(context)
            .filter { it.id in LikesRepository.snapshot(context) }
        if (liked.isEmpty()) return@withLock
        val idx = liked.indexOfFirst { it.id == currentStationId }
        val n = liked.size
        val target = if (idx < 0) (if (forward) 0 else n - 1)
            else (((idx + (if (forward) 1 else -1)) % n) + n) % n
        val targetStation = liked[target]
        // Avoid re-buffering the same station (only liked station, or wrapped onto self).
        if (targetStation.id == currentStationId) return@withLock
        playStation(context, targetStation)
    }
}

/**
 * Holds the current station's ordered candidate URLs so PlaybackService can advance to the next
 * one on a playback error. Process-wide; reset whenever a new station starts playing.
 */
object PlaybackFallback {
    @Volatile private var station: Station? = null
    @Volatile private var candidates: List<String> = emptyList()
    @Volatile private var index: Int = 0

    fun set(station: Station, candidates: List<String>) {
        this.station = station
        this.candidates = candidates
        this.index = 0
    }

    /** The next fallback MediaItem for the current station, or null if exhausted. */
    fun nextItem(): MediaItem? {
        val st = station ?: return null
        if (index + 1 >= candidates.size) return null
        index++
        return PlayerFactory.buildMediaItem(st, candidates[index])
    }
}
