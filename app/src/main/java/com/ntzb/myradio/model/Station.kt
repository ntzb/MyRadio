package com.ntzb.myradio.model

/**
 * A radio station, normalized from the idanplus channels.json model plus our extra stations.
 *
 * The idanplus addon stores the playable address under linkDetails.link, except the `glz`
 * module (Galgalatz / Galei Zahal) which uses linkDetails.live. Some `radio`-module entries
 * are webpages that must be scraped via [regex]; the dynamic modules (glz/100fm/1064fm/sport5)
 * resolve a fresh URL at play time (see StreamResolver).
 *
 * [urls] is an ordered candidate list: primary first, then fallbacks tried on playback error.
 */
data class Station(
    val id: String,
    val name: String,
    val module: String,
    val image: String,          // logo filename bundled in assets/logos, or a remote URL, or ""
    val urls: List<String>,     // ordered: primary + fallbacks (or a page URL for regex stations)
    val regex: String? = null,
    val regexFlags: String? = null,
    val ch: String? = null,     // secondary API/lookup URL used by 100fm/1064fm/sport5
    val rootId: String? = null, // glz player root id
    val adaptive: Boolean = false
) {
    /** URI Coil/asset-loader can use: bundled asset, remote URL, or empty (→ placeholder). */
    val logoUri: String
        get() = when {
            image.isBlank() -> ""
            image.startsWith("http") -> image
            else -> "file:///android_asset/logos/$image"
        }
}
