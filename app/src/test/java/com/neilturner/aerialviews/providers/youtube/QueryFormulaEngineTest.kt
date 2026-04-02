package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Query Formula Engine Tests")
internal class QueryFormulaEngineTest {
    @Test
    @DisplayName("Should sanitize fast-motion query terms")
    fun testSanitizeFastMotionQueryTerms() {
        val sanitized =
            QueryFormulaEngine.sanitizeQueryForAmbientPlaybackForTest(
                "4k fpv canyon flythrough timelapse",
            ).lowercase()

        assertFalse(sanitized.contains("fpv"))
        assertFalse(sanitized.contains("flythrough"))
        assertFalse(sanitized.contains("timelapse"))
    }

    @Test
    @DisplayName("Generated query pool should avoid fast-motion terms")
    fun testGeneratedQueryPoolAvoidsFastMotionTerms() {
        val queries =
            QueryFormulaEngine.generateQueryPool(
                count = 20,
                prefs =
                    QueryFormulaEngine.CategoryPreferences(
                        categoryNature = true,
                        categoryAnimals = false,
                        categoryDrone = true,
                        categoryCities = false,
                        categorySpace = false,
                        categoryOcean = true,
                        categoryWeather = false,
                        categoryWinter = false,
                    ),
                entropySeed = 1234L,
            )

        queries.forEach { query ->
            val normalized = query.lowercase()
            assertFalse(normalized.contains("timelapse"))
            assertFalse(normalized.contains("fpv"))
            assertFalse(normalized.contains("flythrough"))
        }
    }
}