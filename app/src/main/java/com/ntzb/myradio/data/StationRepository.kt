package com.ntzb.myradio.data

import android.content.Context
import com.ntzb.myradio.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Loads the station catalog from two sources, each: freshly cached remote copy → bundled asset.
 *  1. The idanplus radio stations (Fishenzon `channels.json`).
 *  2. Our [EXTRA_STATIONS_URL] `extra_stations.json` (KJazz / WEGE / Nostalgia, and anything you add).
 * Hardcoded [backups] are appended to matching idanplus stations by id.
 */
object StationRepository {

    const val CHANNELS_URL =
        "https://raw.githubusercontent.com/Fishenzon/repo/master/zips/plugin.video.idanplus/channels.json"

    // Points at the SAME extra_stations.json that's bundled in assets, served from your repo.
    // Editing that file + pushing updates the app within 24h (bundled copy is the offline fallback).
    const val EXTRA_STATIONS_URL =
        "https://raw.githubusercontent.com/ntzb/MyRadio/main/app/src/main/assets/extra_stations.json"

    private const val CHANNELS_FILE = "channels.json"
    private const val EXTRA_FILE = "extra_stations.json"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadStations(context: Context): List<Station> = withContext(Dispatchers.IO) {
        val channels = runCatching { parseChannels(readChannels(context)) }.getOrElse { emptyList() }
        val extras = runCatching { parseExtra(readExtra(context)) }.getOrElse { emptyList() }
        (channels + extras)
            .map { st -> st.copy(urls = (st.urls + (backups[st.id] ?: emptyList())).distinct()) }
            .distinctBy { it.id }
    }

    /** Download the latest catalogs into cache. Returns true if the (essential) channels list updated. */
    suspend fun refreshFromRemote(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Best-effort: refresh extras (user repo); ignore failure (placeholder URL / offline).
        runCatching { download(EXTRA_STATIONS_URL, EXTRA_FILE, context) { parseExtra(it) } }
        runCatching {
            download(CHANNELS_URL, CHANNELS_FILE, context) { parseChannels(it) }
        }.getOrDefault(false)
    }

    private inline fun download(
        url: String,
        cacheFile: String,
        context: Context,
        validate: (String) -> List<Station>
    ): Boolean {
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body?.string() ?: return false
            validate(body) // throws if malformed → caller's runCatching rejects, keeps old cache
            File(context.filesDir, cacheFile).writeText(body)
        }
        return true
    }

    private fun readChannels(context: Context): String =
        File(context.filesDir, CHANNELS_FILE).takeIf { it.exists() }?.readText()
            ?: context.assets.open(CHANNELS_FILE).bufferedReader().use { it.readText() }

    private fun readExtra(context: Context): String =
        File(context.filesDir, EXTRA_FILE).takeIf { it.exists() }?.readText()
            ?: context.assets.open(EXTRA_FILE).bufferedReader().use { it.readText() }

    /** Parse the idanplus channels.json (object keyed by id; radio entries only). */
    private fun parseChannels(raw: String): List<Station> {
        val root = json.parseToJsonElement(raw).jsonObject
        val out = ArrayList<Station>()
        for ((id, element) in root) {
            val obj = element as? JsonObject ?: continue
            if (obj.str("type") != "radio") continue
            val ld = obj["linkDetails"]?.jsonObject ?: continue
            val url = ld.str("link") ?: ld.str("live") ?: continue
            out += Station(
                id = id,
                name = obj.str("name") ?: id,
                module = obj.str("module") ?: "radio",
                image = obj.str("image") ?: "",
                urls = listOf(url),
                regex = ld.str("regex"),
                regexFlags = ld.str("flags"),
                ch = ld.str("ch"),
                rootId = ld.str("rootId"),
                adaptive = ld["adaptive"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
        return out
    }

    /** Parse our simple extra_stations.json ({ "stations": [ { id, name, module, image, urls[] } ] }). */
    private fun parseExtra(raw: String): List<Station> {
        val arr = json.parseToJsonElement(raw).jsonObject["stations"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val urls = o["urls"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (urls.isEmpty()) return@mapNotNull null
            Station(
                id = id,
                name = o.str("name") ?: id,
                module = o.str("module") ?: "radio",
                image = o.str("image") ?: "",
                urls = urls,
                regex = o.str("regex"),
                regexFlags = o.str("flags"),
                ch = o.str("ch"),
                rootId = o.str("rootId"),
                adaptive = o["adaptive"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Hardcoded fallback streams (appended after the primary), by idanplus station id. */
    private val backups = mapOf(
        // Kan 88 — verified StreamTheWorld MP3 mirror (progressive, plays even if DASH fails).
        "rd_88" to listOf("https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_88.mp3"),
        "rd_glglz" to listOf("http://glzwizzlv.bynetcdn.com/glglz_mp3"),
        "rd_glz" to listOf("http://glzwizzlv.bynetcdn.com/glz_mp3")
    )
}
