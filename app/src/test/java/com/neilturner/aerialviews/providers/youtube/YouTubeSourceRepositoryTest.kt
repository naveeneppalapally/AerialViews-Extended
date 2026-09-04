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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

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
                    YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to streamSignature("best"),
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
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

    @Test
    @DisplayName("Should invalidate cached stream URLs when YouTube quality target changes")
    fun testGetCachedVideosSnapshotInvalidatesStreamUrlsWhenQualityChanges() = runTest {
        val now = System.currentTimeMillis()
        val cacheDao = FakeYouTubeCacheDao(buildEntries(now))
        val watchHistoryDao = FakeYouTubeWatchHistoryDao()
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                    YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                    YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to streamSignature("1080p"),
                    YouTubeSourceRepository.KEY_QUALITY to "2160p",
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
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

        val cachedEntries = repository.getCachedVideosSnapshot()

        assertTrue(cachedEntries.isNotEmpty())
        assertTrue(cachedEntries.all { it.streamUrl.isBlank() })
        assertEquals(cacheDao.countGoodEntries(), cacheDao.invalidatedStreamUrlCount)
        assertEquals(
            streamSignature("2160p"),
            sharedPreferences.getString(YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE, null),
        )
    }

    @Test
    @DisplayName("Should invalidate cached stream URLs when the stream selection strategy changes")
    fun testGetCachedVideosSnapshotInvalidatesStreamUrlsWhenStrategyChanges() = runTest {
        val now = System.currentTimeMillis()
        val cacheDao = FakeYouTubeCacheDao(buildEntries(now))
        val watchHistoryDao = FakeYouTubeWatchHistoryDao()
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                    YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                    YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to "2160p|videoOnly=true",
                    YouTubeSourceRepository.KEY_QUALITY to "2160p",
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
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

        val cachedEntries = repository.getCachedVideosSnapshot()

        assertTrue(cachedEntries.isNotEmpty())
        assertTrue(cachedEntries.all { it.streamUrl.isBlank() })
        assertEquals(cacheDao.countGoodEntries(), cacheDao.invalidatedStreamUrlCount)
        assertEquals(
            streamSignature("2160p"),
            sharedPreferences.getString(YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE, null),
        )
    }

    @Test
    @DisplayName("Should build the library offline via injected searcher and extractor")
    fun testRefreshPipelineRunsOfflineWithFakes() =
        runTest {
            val cacheDao = FakeYouTubeCacheDao(mutableListOf())
            val watchHistoryDao = FakeYouTubeWatchHistoryDao()
            val sharedPreferences =
                InMemorySharedPreferences(
                    mutableMapOf(
                        YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                        YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                        YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to streamSignature("best"),
                        YouTubeSourceRepository.KEY_QUALITY to "best",
                        YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                        YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
                    ),
                )

            val context = mockPackageContext()
            val repository =
                YouTubeSourceRepository(
                    context = context,
                    cacheDao = cacheDao,
                    watchHistoryDao = watchHistoryDao,
                    sharedPreferences = sharedPreferences,
                    searcher = FakeVideoSearcher(),
                    extractor = FakeStreamExtractor(),
                )

            val entries = repository.refreshSearchResults(replaceExistingCache = true)

            assertTrue(entries.isNotEmpty(), "Expected fakes to produce cache entries without network")
            assertTrue(entries.all { it.streamUrl.startsWith("https://cdn.example.com/") })
            assertEquals(entries.size, cacheDao.countGoodEntries())
        }

    @Test
    @DisplayName("Should preserve Projectivy YouTube UHD quality targets")
    fun testProjectivyPlaybackResolutionQualityFor() {        assertEquals("2160p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("best"))
        assertEquals("2160p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("2160p"))
        assertEquals("1440p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("1440p"))
        assertEquals("1080p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("1080p"))
        assertEquals("720p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("720p"))
        assertEquals("2160p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("  "))
    }

    private fun streamSignature(quality: String): String =
        "$quality|videoOnly=true|selector=v${YouTubeSourceRepository.STREAM_SELECTION_STRATEGY_VERSION}"

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

    private fun mockPackageContext(): Context {
        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"
        return context
    }

    private class FakeVideoSearcher : VideoSearcher {
        private var counter = 0

        override suspend fun searchVideos(
            query: String,
            category: QueryFormulaEngine.ContentCategory?,
        ): List<StreamInfoItem> =
            (1..30).map {
                counter += 1
                StreamInfoItem(
                    0,
                    "https://www.youtube.com/watch?v=fakevideo$counter",
                    "Ambient forest real footage $counter",
                    StreamType.VIDEO_STREAM,
                ).apply {
                    uploaderName = "Fake Nature Channel $counter"
                    setDuration(600L)
                }
            }
    }

    private class FakeStreamExtractor : StreamExtractor {
        override suspend fun extractPlaybackStreams(
            videoPageUrl: String,
            preferredQuality: String,
            preferVideoOnly: Boolean,
            allowAdaptiveManifests: Boolean,
            preferAdaptiveManifests: Boolean,
            preferManifests: Boolean,
        ): YouTubePlaybackUrls {
            val videoId = videoPageUrl.substringAfter("v=").substringBefore("&").ifBlank { "unknown" }
            return YouTubePlaybackUrls(videoUrl = "https://cdn.example.com/$videoId.mp4")
        }
    }

}