package com.ntzb.myradio.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors the app's liked set + now-playing into each widget's OWN Glance state, then refreshes.
 * Glance only recomposes when its managed state changes — reading an external DataStore in
 * provideGlance does NOT trigger a re-render, which is why updateAll alone looked like a no-op.
 */
object WidgetSync {
    val LIKED = stringSetPreferencesKey("w_liked")
    val NP_ID = stringPreferencesKey("w_np_id")
    val NP_NAME = stringPreferencesKey("w_np_name")
    val NP_SONG = stringPreferencesKey("w_np_song")
    val NP_PLAYING = booleanPreferencesKey("w_np_playing")

    // Runs off the main thread: Glance updates do disk I/O and fail silently if called on Main.
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        val liked = runCatching { LikesRepository.snapshot(context) }.getOrDefault(emptySet())
        val np = runCatching { PlaybackSnapshot.read(context) }.getOrNull()
        val ids = runCatching {
            GlanceAppWidgetManager(context).getGlanceIds(RadioWidget::class.java)
        }.getOrDefault(emptyList())
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[LIKED] = liked
                prefs[NP_ID] = np?.stationId ?: ""
                prefs[NP_NAME] = np?.title ?: ""
                prefs[NP_SONG] = np?.song ?: ""
                prefs[NP_PLAYING] = np?.isPlaying ?: false
            }
        }
        runCatching { RadioWidget().updateAll(context) }
    }
}
