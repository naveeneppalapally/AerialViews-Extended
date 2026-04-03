package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("YouTube Source Repository Tests")
internal class YouTubeSourceRepositoryTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    @DisplayName("Should not return the same video twice in a row")
    fun testGetNextVideoUrlAvoidsConsecutiveRepeats() = runTest {
        val now = System.currentTimeMillis()
        val cacheDao = FakeYouTubeCacheDao(buildEntries(now))
        val watchHistoryDao = FakeYouTubeWatchHistoryDao()
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                    YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                    YouTubeSourceRepository.KEY_FIRST_LAUNCH to false,
                    YouTubeSourceRepository.KEY_FIRST_LAUNCH_INDEX to 0,
                ),
            )

        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"

        val repository =
            YouTubeSourceRepository(
                context = context,
                cacheDao = cacheDao,
                watchHistoryDao = watchHistoryDao,
                sharedPreferences = sharedPreferences,
            )

        val playedIds = mutableListOf<String>()
        repeat(10) {
            val streamUrl = repository.getNextVideoUrl()
            assertTrue(streamUrl.startsWith("https://cdn.example.com/video"))
            playedIds += watchHistoryDao.lastPlayedVideoId()
        }

        assertTrue(
            playedIds.zipWithNext().all { (first, second) -> first != second },
            "Expected no consecutive repeat, but got $playedIds",
        )
    }

    private fun buildEntries(now: Long): MutableList<YouTubeCacheEntity> =
        (1..200).map { index ->
            YouTubeCacheEntity(
                videoId = "video$index",
                videoPageUrl = "https://www.youtube.com/watch?v=video$index",
                streamUrl = "https://cdn.example.com/video$index.mp4",
                title = "Ambient nature video $index",
                uploaderName = "channel$index",
                durationSeconds = 600,
                categoryKey = "nature",
                streamUrlExpiresAt = now + 86_400_000L,
                searchCachedAt = now,
                searchQuery = "4K aerial nature ambient",
                isBad = false,
                lastPlayedAt = 0L,
            )
        }.toMutableList()

    private class FakeYouTubeCacheDao(
        private val entries: MutableList<YouTubeCacheEntity>,
    ) : YouTubeCacheDao {
        override fun getAll(): List<YouTubeCacheEntity> = entries.toList()

        override fun getAllGood(): List<YouTubeCacheEntity> = entries.filterNot { it.isBad }

        override fun countGoodEntries(): Int = entries.count { !it.isBad }

        override fun getValidEntries(now: Long): List<YouTubeCacheEntity> =
            entries.filter { !it.isBad && it.streamUrlExpiresAt > now }

        override fun insertAll(entries: List<YouTubeCacheEntity>) {
            this.entries.removeAll { existing -> entries.any { it.videoId == existing.videoId } }
            this.entries.addAll(entries)
        }

        override fun clearAll() {
            entries.clear()
        }

        override fun clearAllGood() {
            entries.removeAll { !it.isBad }
        }

        override fun updateStreamUrl(videoId: String, newUrl: String, newExpiresAt: Long) {
            updateEntry(videoId) { entry ->
                entry.copy(streamUrl = newUrl, streamUrlExpiresAt = newExpiresAt, isBad = false)
            }
        }

        override fun getOldestCachedAt(): Long? = entries.minOfOrNull { it.searchCachedAt }

        override fun getByVideoPageUrl(videoPageUrl: String): YouTubeCacheEntity? =
            entries.firstOrNull { it.videoPageUrl == videoPageUrl }

        override fun markAsBad(videoId: String): Int {
            val before = entries.firstOrNull { it.videoId == videoId } ?: return 0
            if (before.isBad) {
                return 0
            }
            updateEntry(videoId) { it.copy(isBad = true) }
            return 1
        }

        override fun markAsPlayed(videoId: String, timestamp: Long) {
            updateEntry(videoId) { it.copy(lastPlayedAt = timestamp) }
        }

        override fun resetPlayHistory() {
            entries.replaceAll { it.copy(lastPlayedAt = 0L) }
        }

        override fun deleteByNotInCategories(allowedCategoryKeys: List<String>): Int {
            val before = entries.size
            entries.removeAll { !it.isBad && it.categoryKey.isNotBlank() && it.categoryKey !in allowedCategoryKeys }
            return before - entries.size
        }

        override fun deleteByVideoIds(videoIds: List<String>): Int {
            val before = entries.size
            entries.removeAll { it.videoId in videoIds }
            return before - entries.size
        }

        override fun getUnwatchedEntry(cutoff: Long): YouTubeCacheEntity? =
            entries.firstOrNull { !it.isBad && (it.lastPlayedAt == 0L || it.lastPlayedAt < cutoff) }

        override fun getLeastRecentlyPlayed(): YouTubeCacheEntity? =
            entries.filterNot { it.isBad }.minByOrNull { it.lastPlayedAt }

        private fun updateEntry(
            videoId: String,
            transform: (YouTubeCacheEntity) -> YouTubeCacheEntity,
        ) {
            val index = entries.indexOfFirst { it.videoId == videoId }
            if (index >= 0) {
                entries[index] = transform(entries[index])
            }
        }
    }

    private class FakeYouTubeWatchHistoryDao : YouTubeWatchHistoryDao {
        private val history = mutableListOf<YouTubeWatchHistoryEntity>()
        private var nextHistoryId = 1L

        override fun insert(entry: YouTubeWatchHistoryEntity) {
            history += entry.copy(historyId = nextHistoryId++)
        }

        override fun recentHistory(limit: Int): List<YouTubeWatchHistoryEntity> =
            history
                .sortedWith(compareByDescending<YouTubeWatchHistoryEntity> { it.playedAt }.thenByDescending { it.historyId })
                .take(limit)

        override fun trimToLimit(limit: Int) {
            val retained = recentHistory(limit).map { it.historyId }.toSet()
            history.removeAll { it.historyId !in retained }
        }

        fun lastPlayedVideoId(): String = history.lastOrNull()?.videoId ?: error("Playback was not recorded")
    }

    private class InMemorySharedPreferences(
        initialValues: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            when (val value = values[key]) {
                is Set<*> -> value.filterIsInstance<String>().toMutableSet()
                else -> defValues
            }

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        }

        private class Editor(
            private val values: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyChange(key, value)

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                applyChange(key, values?.toSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyChange(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyChange(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyChange(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyChange(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let { removals += it }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) {
                    values.clear()
                }
                removals.forEach(values::remove)
                values.putAll(pending)
            }

            private fun applyChange(
                key: String?,
                value: Any?,
            ): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                }
                return this
            }
        }
    }
}