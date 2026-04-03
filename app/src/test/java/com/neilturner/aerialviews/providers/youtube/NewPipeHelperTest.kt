package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NewPipe Helper Tests")
internal class NewPipeHelperTest {
    @Test
    @DisplayName("Should treat dramatic pipe-separated titles as human content")
    fun testDramaticPipeSeparatedTitlesRejected() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Snow Leopard fight Mountain Goat | Wildlife Documentary"))
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Unbelievable | Cliff Chase | Amazing footage"))
    }

    @Test
    @DisplayName("Should allow ambient pipe-separated titles")
    fun testAmbientPipeSeparatedTitlesAllowed() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Japan 4K | Nature Walk | Ambient Sounds"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norway Fjords | Aerial 4K | No Music"))
    }

    @Test
    @DisplayName("Should reject documentary-style titles")
    fun testDocumentaryTitleRejected() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Wildlife Documentary Animals | Nature Film"))
    }

    @Test
    @DisplayName("Should allow ambient titles through filter")
    fun testAmbientTitlesAllowed() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("4K Japan Forest Walk Ambient No Music"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norwegian Fjords Aerial Drone 4K"))
    }
}