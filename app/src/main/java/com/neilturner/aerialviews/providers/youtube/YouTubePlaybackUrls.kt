package com.neilturner.aerialviews.providers.youtube

data class YouTubePlaybackUrls(
    val videoUrl: String,
    val audioUrl: String = "",
) {
    val hasSeparateAudio: Boolean
        get() = audioUrl.isNotBlank()
}
