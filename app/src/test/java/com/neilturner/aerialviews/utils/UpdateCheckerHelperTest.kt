package com.neilturner.aerialviews.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Update Checker Helper Tests")
internal class UpdateCheckerHelperTest {
    @Test
    @DisplayName("Should treat beta suffix as same base version")
    fun testBetaSuffixVersionComparison() {
        assertFalse(UpdateCheckerHelper.isNewerVersion("v1.3.6", "1.3.6-beta12"))
    }

    @Test
    @DisplayName("Should detect newer patch version from beta install")
    fun testNewerPatchVersionComparisonFromBetaInstall() {
        assertTrue(UpdateCheckerHelper.isNewerVersion("v1.3.7", "1.3.6-beta12"))
    }

    @Test
    @DisplayName("Should treat 1.0 and v1.0.0 as the same version")
    fun testEquivalentSemanticVersions() {
        assertTrue(UpdateCheckerHelper.isSameVersion("v1.0.0", "1.0"))
    }
}