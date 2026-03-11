package com.neilturner.aerialviews.models.prefs

import com.chibatching.kotpref.KotprefModel
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository

object YouTubeVideoPrefs : KotprefModel() {
    override val kotprefName = "${context.packageName}_preferences"

    var enabled by booleanPref(true, "yt_enabled")
    var query by stringPref(YouTubeSourceRepository.DEFAULT_QUERY, YouTubeSourceRepository.KEY_QUERY)
    var quality by stringPref(YouTubeSourceRepository.DEFAULT_QUALITY, YouTubeSourceRepository.KEY_QUALITY)
    var minDurationMinutes by intPref(
        YouTubeSourceRepository.DEFAULT_MIN_DURATION_MINUTES,
        YouTubeSourceRepository.KEY_MIN_DURATION,
    )
    var mixWeight by stringPref(YouTubeSourceRepository.DEFAULT_MIX_WEIGHT, YouTubeSourceRepository.KEY_MIX_WEIGHT)
    var shuffle by booleanPref(YouTubeSourceRepository.DEFAULT_SHUFFLE, YouTubeSourceRepository.KEY_SHUFFLE)
    var count by stringPref("-1", YouTubeSourceRepository.KEY_COUNT)
}
