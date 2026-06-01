package com.ntzb.myradio.data

import com.ntzb.myradio.model.Station
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
 * Turns a [Station] into a directly playable stream URL, mirroring the idanplus per-module logic:
 *  - kan         → play the DASH .livx directly (strip the long ?dvr timeshift → live edge)
 *  - radio+regex → fetch the page and extract the stream (the .aspx / webpage stations)
 *  - 100fm/1064fm→ hit the station's JSON/API (`ch`) for the current URL
 *  - everything else (incl. glz, plain icecast) → the static link/live URL
 * Any failure falls back to the hardcoded [Station.backup], then the raw URL.
 */
object StreamResolver {

    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the ordered list of playable URLs to try: a freshly resolved URL first (for the
     * dynamic modules), then the station's static [Station.urls] fallbacks. For regex stations
     * the first static URL is a webpage, so it is dropped (only its real backups are kept).
     */
    suspend fun resolveCandidates(station: Station): List<String> = withContext(Dispatchers.IO) {
        val resolved = runCatching {
            when (station.module) {
                "100fm" -> resolve100fm(station)
                "1064fm" -> resolve1064fm(station)
                "radio" -> if (!station.regex.isNullOrBlank()) resolveRegex(station) else null
                else -> null // kan, glz, plain radio, extras → use the static URLs directly
            }
        }.getOrNull()

        val isRegexPage = station.module == "radio" && !station.regex.isNullOrBlank()
        val base = if (isRegexPage) station.urls.drop(1) else station.urls
        (listOfNotNull(resolved) + base)
            .map(::stripDvr)
            .filter { it.startsWith("http") }
            .distinct()
            .ifEmpty { station.urls }
    }

    private fun stripDvr(url: String): String = url.replace(Regex("\\?dvr=\\d+"), "")

    private fun resolveRegex(station: Station): String {
        val page = httpText(station.urls.first())
        val opts = buildSet {
            add(RegexOption.DOT_MATCHES_ALL)
            station.regexFlags?.let { f ->
                if (f.contains("I", true)) add(RegexOption.IGNORE_CASE)
                if (f.contains("M", true)) add(RegexOption.MULTILINE)
            }
        }
        val match = Regex(station.regex!!, opts).find(page)
            ?: error("regex no match for ${station.id}")
        val found = match.groupValues.getOrElse(1) { match.value }.trim()
        return if (found.startsWith("http")) found else "http://$found"
    }

    private fun resolve100fm(station: Station): String {
        val ch = station.ch ?: error("100fm missing ch")
        val obj = json.parseToJsonElement(httpText(ch)).jsonObject
        return obj["stations"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("audio")?.jsonPrimitive?.contentOrNull
            ?: error("100fm no audio")
    }

    private fun resolve1064fm(station: Station): String {
        val ch = station.ch ?: error("1064fm missing ch")
        val text = httpText(ch)
        val raw = Regex("\"webapp\\.broadcast_link\":\"(.*?)\"").find(text)?.groupValues?.get(1)
            ?: error("1064fm no link")
        return raw.replace("\\u002F", "/").replace("\\/", "/")
    }

    private fun httpText(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", Constants.USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string() ?: error("empty body for $url")
        }
    }
}
