package com.ntzb.myradio.data

import com.ntzb.myradio.player.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves "now playing" for stations whose stream carries no ICY metadata, by polling each
 * station's own now-playing feed. Broadcasters differ:
 *   - Kan (DASH)        → ACRCloud JSON proxy (see [KanNowPlaying])
 *   - glz / Galgalatz   → Dalet "onair.xml" (<Current><titleName>/<artistName>)
 *   - Nostalgia 96.3    → WordPress admin-ajax JSON, guarded by a rotating _wpnonce we scrape
 * Add more stations by mapping their id to a feed here.
 */
object NowPlayingResolver {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** Station id → Dalet on-air XML feed. */
    private val xmlSources = mapOf(
        "rd_glglz" to "https://glzxml.blob.core.windows.net/dalet/glglz-onair/onair.xml",
        "rd_glz" to "https://glzxml.blob.core.windows.net/dalet/glz-onair/onair.xml"
    )

    /** Station id → public Firestore REST doc (fields.song_name / fields.artist_name). */
    private val firestoreSources = mapOf(
        // ECO 99FM reads streamed_content/program from its public Firebase project.
        "rd_99" to "https://firestore.googleapis.com/v1/projects/eco-99-production/databases/(default)/documents/streamed_content/program?key=AIzaSyCN9DRdNHtF4rixiNqhz8CfzUgIKtAx6jo"
    )

    /** Station id → "track" XML feed (<track><artist>/<name>). */
    private val trackXmlSources = mapOf(
        "rd_100" to "https://digital.100fm.co.il/api/nowplaying/100fm/0"
    )

    private const val NOSTALGIA_ID = "x_nostalgia"
    private const val NOSTALGIA_PAGE = "https://www.963fm.co.il/"
    private const val NOSTALGIA_AJAX =
        "https://www.963fm.co.il/wp-admin/admin-ajax.php?action=nostalgia_nowplaying&_wpnonce="

    @Volatile private var nostalgiaNonce: String? = null

    fun hasSource(stationId: String): Boolean =
        KanNowPlaying.hasChannel(stationId) ||
            xmlSources.containsKey(stationId) ||
            firestoreSources.containsKey(stationId) ||
            trackXmlSources.containsKey(stationId) ||
            stationId == NOSTALGIA_ID

    /** "Artist - Title" (or "Title"), or null if nothing / no source / error. */
    suspend fun fetch(stationId: String): String? = withContext(Dispatchers.IO) {
        when {
            KanNowPlaying.hasChannel(stationId) -> KanNowPlaying.fetchSong(stationId)
            xmlSources.containsKey(stationId) -> fetchDalet(xmlSources.getValue(stationId))
            firestoreSources.containsKey(stationId) -> fetchFirestore(firestoreSources.getValue(stationId))
            trackXmlSources.containsKey(stationId) -> fetchTrackXml(trackXmlSources.getValue(stationId))
            stationId == NOSTALGIA_ID -> fetchNostalgia()
            else -> null
        }
    }

    // --- "track" XML (100FM): <track><artist>/<name> ---
    private fun fetchTrackXml(url: String): String? {
        val xml = httpGet(url) ?: return null
        return combine(xmlTag(xml, "artist"), xmlTag(xml, "name"))
    }

    // --- Dalet on-air XML (glz / Galgalatz) ---
    private fun fetchDalet(url: String): String? {
        val xml = httpGet(url) ?: return null
        val current = Regex("<Current>(.*?)</Current>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return null
        return combine(xmlTag(current, "artistName"), xmlTag(current, "titleName"))
    }

    private fun xmlTag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape)

    // --- Firestore REST (ECO 99FM): fields.<key>.stringValue ---
    private fun fetchFirestore(url: String): String? {
        val body = httpGet(url) ?: return null
        val fields = runCatching { json.parseToJsonElement(body).jsonObject["fields"]?.jsonObject }
            .getOrNull() ?: return null
        fun sv(key: String) =
            fields[key]?.jsonObject?.get("stringValue")?.jsonPrimitive?.contentOrNull
        return combine(sv("artist_name"), sv("song_name"))
    }

    // --- Nostalgia 96.3 (WordPress admin-ajax, rotating nonce) ---
    private fun fetchNostalgia(): String? {
        val nonce = nostalgiaNonce ?: scrapeNostalgiaNonce()?.also { nostalgiaNonce = it } ?: return null
        var body = httpGet(NOSTALGIA_AJAX + nonce)
        if (body == null || !body.contains("\"success\":true")) {
            // Nonce likely expired — refresh from the page once and retry.
            val fresh = scrapeNostalgiaNonce() ?: return null
            nostalgiaNonce = fresh
            body = httpGet(NOSTALGIA_AJAX + fresh) ?: return null
        }
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }
            .getOrNull() ?: return null
        return combine(
            data["artist"]?.jsonPrimitive?.contentOrNull,
            data["title"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun scrapeNostalgiaNonce(): String? =
        httpGet(NOSTALGIA_PAGE)?.let {
            Regex("data-nonce=\"([a-f0-9]{8,})\"").find(it)?.groupValues?.get(1)
        }

    // --- shared helpers ---
    private fun combine(artist: String?, title: String?): String? {
        val t = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val a = artist?.trim()?.takeIf { it.isNotBlank() }
        return if (a != null) "$a - $t" else t
    }

    private fun httpGet(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", Constants.USER_AGENT).build()
        client.newCall(req).execute().use { resp -> resp.body?.string() }
    }.getOrNull()

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
}
