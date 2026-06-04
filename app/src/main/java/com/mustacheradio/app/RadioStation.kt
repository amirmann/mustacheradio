package com.mustacheradio.app

import androidx.annotation.DrawableRes

data class RadioStation(
    val id: String,
    val name: String,
    val description: String,
    val streamUrl: String,
    @DrawableRes val iconRes: Int
)
