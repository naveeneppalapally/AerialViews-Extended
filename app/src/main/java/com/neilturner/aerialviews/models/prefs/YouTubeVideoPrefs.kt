package com.neilturner.aerialviews.models.prefs

import com.chibatching.kotpref.KotprefModel
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository

object YouTubeVideoPrefs : KotprefModel() {
    override val kotprefName = "${context.packageName}_preferences"

    var enabled by booleanPref(true, "yt_enabled")
    var query by stringPref(YouTubeSourceRepository.DEFAULT_QUERY, YouTubeSourceRepository.KEY_QUERY)
    var quality by stringPref(YouTubeSourceRepository.DEFAULT_QUALITY, YouTubeSourceRepository.KEY_QUALITY)
    var mixWeight by stringPref(YouTubeSourceRepository.DEFAULT_MIX_WEIGHT, YouTubeSourceRepository.KEY_MIX_WEIGHT)
    var shuffle by booleanPref(YouTubeSourceRepository.DEFAULT_SHUFFLE, YouTubeSourceRepository.KEY_SHUFFLE)
    var playbackLengthMode by stringPref("limit", "yt_playback_length_mode")
    var playbackMaxMinutesStr by stringPref("12", "yt_playback_max_minutes")
    var playbackMaxMinutes: Int
        get() = playbackMaxMinutesStr.toIntOrNull()?.coerceAtLeast(1) ?: 12
        set(value) { playbackMaxMinutesStr = value.coerceAtLeast(1).toString() }
    var count by stringPref("-1", YouTubeSourceRepository.KEY_COUNT)
    var categoryNature by booleanPref(true, "yt_category_nature")
    var categoryAnimals by booleanPref(true, "yt_category_animals")
    var categoryDrone by booleanPref(true, "yt_category_drone")
    var categoryCities by booleanPref(true, "yt_category_cities")
    var categorySpace by booleanPref(true, "yt_category_space")
    var categoryOcean by booleanPref(true, "yt_category_ocean")
    var categoryWeather by booleanPref(true, "yt_category_weather")
    var categoryWinter by booleanPref(true, "yt_category_winter")
}
