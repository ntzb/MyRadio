package com.ntzb.myradio.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.glance.appwidget.updateAll
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ntzb.myradio.data.NowPlaying
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StreamResolver
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.widget.RadioWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-wide single MediaController shared by the UI and the widget, so commands from either
 * drive the same session without controller-release races. The controller lives for the process.
 */
object PlayerController {

    private val mutex = Mutex()
    @Volatile private var controller: MediaController? = null

    // MediaController must only be touched on the main thread (it throws otherwise). Widget
    // ActionCallbacks run on a background thread, so the whole get() runs on Main.
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
        val candidates = StreamResolver.resolveCandidates(station)   // resolves on IO
        PlaybackFallback.set(station, candidates)
        val item = PlayerFactory.buildMediaItem(station, candidates.first())
        val c = get(context)
        withContext(Dispatchers.Main) {
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
        // Reflect the new station in the widget immediately; the service listener follows up
        // with ICY/Kan song updates. (Keeps the header/title from lagging on station switch.)
        PlaybackSnapshot.write(context, NowPlaying(station.id, station.name, "", true))
        RadioWidget().updateAll(context)
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
        val c = get(context)
        // Stop AND clear the item so the now-playing strip/widget reset to "Not playing".
        withContext(Dispatchers.Main) {
            c.stop()
            c.clearMediaItems()
        }
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
