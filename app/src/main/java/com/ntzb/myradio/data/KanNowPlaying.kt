package com.ntzb.myradio.data

import com.ntzb.myradio.player.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Kan radio (DASH) streams carry no in-stream song metadata. Kan's website overlays now-playing
 * from ACRCloud via its own proxy: GET /api/arc-cloud/get-live-track-data?channelId=N
 *   → {"title":"...","artists":["..."],"programData":{"programName":"..."}}  (empty body = nothing)
 * We poll the same endpoint for the playing Kan station and surface the title.
 */
object KanNowPlaying {

    // idanplus station id -> Kan ACRCloud channelId (verified from kan.org.il station pages).
    private val channels = mapOf(
        "rd_88" to 4,        // כאן 88
        "rd_music" to 5      // כאן קול המוסיקה
        // Add when known: rd_bet, rd_gimel, rd_moreshet, rd_reka, rd_makan, rd_culture
    )

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    fun hasChannel(stationId: String): Boolean = channels.containsKey(stationId)

    /** Current "Artist - Title" for a Kan station, or null if none / not a Kan station / error. */
    suspend fun fetchSong(stationId: String): String? = withContext(Dispatchers.IO) {
        val cid = channels[stationId] ?: return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("https://www.kan.org.il/api/arc-cloud/get-live-track-data?channelId=$cid")
                .header("User-Agent", Constants.USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return@use null
                val obj = json.parseToJsonElement(body).jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@use null
                val artist = obj["artists"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.joinToString(", ")
                    .orEmpty()
                if (artist.isNotBlank()) "$artist - $title" else title
            }
        }.getOrNull()
    }
}
