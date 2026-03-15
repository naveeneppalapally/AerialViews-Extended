package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewPipeHelperHumanContentFilterTest {
    @Test
    fun rejectsHumanVlogAndTopListTitles() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("My Morning Routine 2024"))
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("TOP 10 Most Beautiful Places"))
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Come With Me To Japan VLOG"))
    }

    @Test
    fun allowsAmbientNatureTitles() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("BBC Earth Ocean Wonders"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norway Fjords Aerial 4K Timelapse"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("4K Forest Walk Japan"))
    }
}
