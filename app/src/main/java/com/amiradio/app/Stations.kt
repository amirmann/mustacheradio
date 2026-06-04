package com.amiradio.app

import com.amiradio.app.R

/**
 * Single source of truth for the radio station catalogue.
 * Both the UI (MainActivity) and the playback service (RadioPlaybackService)
 * reference this object so the list is defined exactly once.
 */
object Stations {

    val ALL: List<RadioStation> = listOf(
        RadioStation(
            id          = "galatz",
            name        = "Galatz (גלצ)",
            description = "Gal Tzahal - IDF Radio",
            streamUrl   = "https://glzwizzlv.bynetcdn.com/glz_mp3",
            iconRes     = R.drawable.ic_galatz
        ),
        RadioStation(
            id          = "galgalatz",
            name        = "Galgalatz (גלגלצ)",
            description = "Israel's Popular Music Station",
            streamUrl   = "https://glzwizzlv.bynetcdn.com/glglz_mp3",
            iconRes     = R.drawable.ic_galgalatz
        ),
        RadioStation(
            id          = "radius100fm",
            name        = "Radius 100FM",
            description = "Israel's Leading Music Station",
            streamUrl   = "https://cdn.cybercdn.live/Radios_100FM/Audio/icecast.audio",
            iconRes     = R.drawable.ic_radius100
        ),
        RadioStation(
            id          = "eco99fm",
            name        = "Eco 99FM",
            description = "Israel's Alternative Radio",
            streamUrl   = "http://eco01.livecdn.biz/ecolive/99fm_aac/icecast.audio",
            iconRes     = R.drawable.ic_eco99
        ),
        RadioStation(
            id          = "88fm",
            name        = "88FM",
            description = "Tel Aviv's Urban Radio",
            streamUrl   = "https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_88.mp3",
            iconRes     = R.drawable.ic_88fm
        ),
        RadioStation(
            id          = "kanbet",
            name        = "Kan Bet (כאן ב)",
            description = "Israel Public Broadcasting",
            streamUrl   = "https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_BET.mp3",
            iconRes     = R.drawable.ic_kanbet
        ),
        RadioStation(
            id          = "103fm",
            name        = "103FM",
            description = "Israel's Hit Music Station",
            streamUrl   = "https://cdn.cybercdn.live/103FM/Live/icecast.audio",
            iconRes     = R.drawable.ic_103fm
        ),
        RadioStation(
            id          = "102fm",
            name        = "102FM - Radio Tel Aviv",
            description = "Tel Aviv's Music Station",
            streamUrl   = "https://102.livecdn.biz/102fm_aac",
            iconRes     = R.drawable.ic_102fm
        )
    )

    /** Fast O(1) lookup by station id. */
    val BY_ID: Map<String, RadioStation> = ALL.associateBy { it.id }
}
