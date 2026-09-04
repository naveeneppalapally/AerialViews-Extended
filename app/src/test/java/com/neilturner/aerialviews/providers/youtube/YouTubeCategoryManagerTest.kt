package com.neilturner.aerialviews.providers.youtube

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("YouTube Category Manager Tests")
internal class YouTubeCategoryManagerTest {
    @Test
    @DisplayName("Should split slots evenly with the remainder distributed in order")
    fun testAllocateCategoryTargets() =
        runTest {
            val manager = YouTubeCategoryManager(FakeYouTubeCacheDao(mutableListOf()), prefs())

            val targets = manager.allocateCategoryTargets(listOf("nature", "ocean", "drone"), 200)

            assertEquals(200, targets.values.sum())
            assertTrue(targets.values.all { it in 66..67 })
            assertEquals(emptyMap<String, Int>(), manager.allocateCategoryTargets(emptyList(), 200))
        }

    @Test
    @DisplayName("Should evict already-seen videos before unseen ones")
    fun testRebalanceEvictsSeenFirst() {
        val now = System.currentTimeMillis()
        val manager = YouTubeCategoryManager(FakeYouTubeCacheDao(mutableListOf()), prefs())
        val entries =
            (1..10).map { index ->
                entry("nature$index", "Forest trail $index", category = "nature")
            } + listOf(entry("seen", "Mountain valley", category = "nature", lastPlayedAt = now))

        val outcome =
            manager.rebalanceOverQuotaCategories(
                entries = entries,
                targets = mapOf("nature" to 5),
                recentPlaybackCutoff = now - 1_000L,
            )

        assertTrue(outcome.evictedVideoIds.contains("seen"))
        assertEquals(6, outcome.evictedVideoIds.size)
    }

    @Test
    @DisplayName("Should preview removal counts without touching the database")
    fun testPreviewCategoryRemovalSnapshot() =
        runTest {
            val cacheDao =
                FakeYouTubeCacheDao(
                    mutableListOf(
                        entry("nature1", "Forest trail", category = "nature"),
                        entry("ocean1", "Ocean waves beach", category = "ocean"),
                    ),
                )
            val manager = YouTubeCategoryManager(cacheDao, prefs("ocean"))

            val preview = manager.previewCategoryRemovalSnapshot()

            assertEquals(1, preview.removedCount)
            assertEquals(1, preview.remainingCount)
            assertEquals(2, cacheDao.countGoodEntries())
        }

    @Test
    @DisplayName("Should keep uncategorized entries when filtering")
    fun testFilteredExistingEntriesKeepsUncategorized() {
        val manager = YouTubeCategoryManager(FakeYouTubeCacheDao(mutableListOf()), prefs())
        val entries =
            listOf(
                entry("nature1", "Forest trail", category = "nature"),
                entry("mystery", "Some clip", category = ""),
            )

        val filtered = manager.filteredExistingEntries(entries)

        assertEquals(2, filtered.size)
    }

    @Test
    @DisplayName("Should rank deficit categories largest-first")
    fun testDeficitPriorityList() {
        val manager = YouTubeCategoryManager(FakeYouTubeCacheDao(mutableListOf()), prefs())

        val order =
            manager.computeDeficitPriorityList(
                targets = linkedMapOf("nature" to 10, "ocean" to 10, "drone" to 10),
                counts = mapOf("nature" to 9, "ocean" to 2, "drone" to 10),
            )

        assertEquals(listOf("ocean", "nature"), order)
    }

    private fun prefs(vararg enabled: String): InMemorySharedPreferences {
        val values = mutableMapOf<String, Any?>()
        for (category in QueryFormulaEngine.ContentCategory.entries) {
            values["yt_category_${category.key}"] =
                enabled.isEmpty() || category.key in enabled
        }
        return InMemorySharedPreferences(values)
    }

    private fun entry(
        videoId: String,
        title: String,
        category: String,
        lastPlayedAt: Long = 0L,
    ): YouTubeCacheEntity =
        YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = "https://www.youtube.com/watch?v=$videoId",
            streamUrl = "https://cdn.example.com/$videoId.mp4",
            title = title,
            uploaderName = "Some Channel",
            durationSeconds = 600,
            categoryKey = category,
            streamUrlExpiresAt = System.currentTimeMillis() + 86_400_000L,
            searchCachedAt = System.currentTimeMillis(),
            searchQuery = "4K aerial nature ambient",
            lastPlayedAt = lastPlayedAt,
        )
}
