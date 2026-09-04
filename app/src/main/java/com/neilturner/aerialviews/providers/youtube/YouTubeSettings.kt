package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences

/**
 * The single reader for YouTube behavior settings (categories, quality,
 * query, shuffle). Previously these keys were read ad-hoc across the
 * repository while the UI wrote them via Kotpref — key/default drift
 * between the two already caused one critical bug. State keys (histories,
 * signatures, counts, timestamps) stay with their owners; this covers
 * settings only.
 */
data class YouTubeSettings(
    val categories: QueryFormulaEngine.CategoryPreferences,
    val quality: String,
    val query: String,
    val shuffle: Boolean,
) {
    companion object {
        fun read(sharedPreferences: SharedPreferences): YouTubeSettings =
            YouTubeSettings(
                categories =
                    QueryFormulaEngine.CategoryPreferences(
                        categoryNature =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_NATURE,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_NATURE,
                            ),
                        categoryAnimals =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_ANIMALS,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_ANIMALS,
                            ),
                        categoryDrone =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_DRONE,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_DRONE,
                            ),
                        categoryCities =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_CITIES,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_CITIES,
                            ),
                        categorySpace =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_SPACE,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_SPACE,
                            ),
                        categoryOcean =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_OCEAN,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_OCEAN,
                            ),
                        categoryWeather =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_WEATHER,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_WEATHER,
                            ),
                        categoryWinter =
                            sharedPreferences.getBoolean(
                                YouTubeSourceRepository.KEY_CATEGORY_WINTER,
                                YouTubeSourceRepository.DEFAULT_CATEGORY_WINTER,
                            ),
                    ),
                quality =
                    sharedPreferences
                        .getString(
                            YouTubeSourceRepository.KEY_QUALITY,
                            YouTubeSourceRepository.DEFAULT_QUALITY,
                        )?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: YouTubeSourceRepository.DEFAULT_QUALITY,
                query =
                    sharedPreferences
                        .getString(
                            YouTubeSourceRepository.KEY_QUERY,
                            YouTubeSourceRepository.DEFAULT_QUERY,
                        )?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: YouTubeSourceRepository.DEFAULT_QUERY,
                shuffle =
                    sharedPreferences.getBoolean(
                        YouTubeSourceRepository.KEY_SHUFFLE,
                        YouTubeSourceRepository.DEFAULT_SHUFFLE,
                    ),
            )
    }
}
