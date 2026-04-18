package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class YouTubeFeatureTest {
    @Test
    @DisplayName("Defaults follow the reported display height on TVs")
    fun tvUsesReportedDisplayHeight() {
        assertEquals("1080p", YouTubeFeature.defaultQualityForDisplay(1080))
        assertEquals("2160p", YouTubeFeature.defaultQualityForDisplay(2160))
    }

    @Test
    @DisplayName("Non-TV devices still follow the reported display height")
    fun nonTvUsesReportedHeight() {
        assertEquals("2160p", YouTubeFeature.defaultQualityForDisplay(2160))
        assertEquals("1440p", YouTubeFeature.defaultQualityForDisplay(1440))
        assertEquals("1080p", YouTubeFeature.defaultQualityForDisplay(1080))
        assertEquals("720p", YouTubeFeature.defaultQualityForDisplay(720))
    }
}