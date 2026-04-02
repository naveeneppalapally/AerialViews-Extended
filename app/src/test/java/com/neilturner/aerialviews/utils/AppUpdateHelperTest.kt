package com.neilturner.aerialviews.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("App Update Helper Tests")
internal class AppUpdateHelperTest {
    @Test
    @DisplayName("Should treat higher semantic versions as newer")
    fun testSemanticVersionComparison() {
        assertTrue(AppUpdateHelper.isNewerVersionForTest("v1.1.0", "1.0"))
    }

    @Test
    @DisplayName("Should treat stable releases as newer than matching beta builds")
    fun testStableBeatsPrerelease() {
        assertTrue(AppUpdateHelper.isNewerVersionForTest("1.0", "1.0-beta12"))
    }

    @Test
    @DisplayName("Should compare prerelease numeric suffixes")
    fun testPrereleaseNumericComparison() {
        assertTrue(AppUpdateHelper.compareVersionsForTest("1.0-beta13", "1.0-beta12") > 0)
    }

    @Test
    @DisplayName("Should not report older versions as newer")
    fun testOlderVersionIsNotNewer() {
        assertFalse(AppUpdateHelper.isNewerVersionForTest("1.0-beta11", "1.0-beta12"))
    }

    @Test
    @DisplayName("Should mark non-release builds as local builds when versions match")
    fun testLocalBuildAvailability() {
        assertEquals(
            AppUpdateHelper.Availability.LOCAL_BUILD,
            AppUpdateHelper.resolveAvailabilityForTest("1.0", "1.0", "nonMinifiedRelease"),
        )
    }

    @Test
    @DisplayName("Should report when no GitHub releases exist")
    fun testNoReleaseAvailability() {
        assertEquals(
            AppUpdateHelper.Availability.NO_RELEASES,
            AppUpdateHelper.resolveAvailabilityForTest("", "1.0", "release"),
        )
    }
}