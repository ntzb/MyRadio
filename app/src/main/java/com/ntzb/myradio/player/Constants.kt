package com.ntzb.myradio.player

object Constants {
    const val ACTION_PLAY_STATION = "com.ntzb.myradio.PLAY_STATION"
    const val ACTION_STOP = "com.ntzb.myradio.STOP"
    const val EXTRA_STATION_ID = "station_id"

    // A normal mobile browser UA — some Israeli CDNs reject empty/odd agents.
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
}
