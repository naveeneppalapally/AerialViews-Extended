package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class YouTubeFeatureTest {
    @Test
    @DisplayName("TVs default to 4K even when the reported display height is 1080p")
    fun tvDefaultsToUhd() {
        assertEquals("2160p", YouTubeFeature.defaultQualityForDisplay(1080, isTv = true))
    }

    @Test
    @DisplayName("Non-TV devices still follow the reported display height")
    fun nonTvUsesReportedHeight() {
        assertEquals("2160p", YouTubeFeature.defaultQualityForDisplay(2160, isTv = false))
        assertEquals("1440p", YouTubeFeature.defaultQualityForDisplay(1440, isTv = false))
        assertEquals("1080p", YouTubeFeature.defaultQualityForDisplay(1080, isTv = false))
        assertEquals("720p", YouTubeFeature.defaultQualityForDisplay(720, isTv = false))
    }
}