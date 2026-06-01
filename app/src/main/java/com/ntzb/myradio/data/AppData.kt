package com.ntzb.myradio.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore("myradio")

/** Persistent set of liked station ids. */
object LikesRepository {
    private val LIKED = stringSetPreferencesKey("liked_ids")

    fun likedIds(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { it[LIKED] ?: emptySet() }

    suspend fun snapshot(context: Context): Set<String> = likedIds(context).first()

    suspend fun toggle(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[LIKED] ?: emptySet()
            prefs[LIKED] = if (id in current) current - id else current + id
        }
    }
}

/** Now-playing snapshot, so a freshly launched UI can render the last-known state. */
data class NowPlaying(
    val stationId: String? = null,
    val title: String = "",
    val song: String = "",
    val isPlaying: Boolean = false
)

object PlaybackSnapshot {
    private val STATION = stringPreferencesKey("np_station")
    private val TITLE = stringPreferencesKey("np_title")
    private val SONG = stringPreferencesKey("np_song")
    private val PLAYING = booleanPreferencesKey("np_playing")

    fun flow(context: Context): Flow<NowPlaying> = context.dataStore.data.map { p ->
        NowPlaying(
            stationId = p[STATION],
            title = p[TITLE] ?: "",
            song = p[SONG] ?: "",
            isPlaying = p[PLAYING] ?: false
        )
    }

    suspend fun read(context: Context): NowPlaying = flow(context).first()

    suspend fun write(context: Context, np: NowPlaying) {
        context.dataStore.edit { p ->
            np.stationId?.let { p[STATION] = it } ?: p.remove(STATION)
            p[TITLE] = np.title
            p[SONG] = np.song
            p[PLAYING] = np.isPlaying
        }
    }
}
