package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem

internal class YouTubeSourceRepositoryTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `get next video url refreshes cache when empty`() = runTest {
        mockkObject(NewPipeHelper)
        val dao = FakeYouTubeCacheDao()
        val prefs = createPreferences()
        val repository = YouTubeSourceRepository(dao, prefs)

        coEvery { NewPipeHelper.searchVideos(any(), any()) } returns
            listOf(searchItem("abc123", "Ambient Flight"))
        coEvery { NewPipeHelper.extractStreamUrl("https://www.youtube.com/watch?v=abc123", "1080p", false) } returns
            "https://cdn.example.com/stream1.mpd"

        val result = repository.getNextVideoUrl()

        assertEquals("https://cdn.example.com/stream1.mpd", result)
        assertEquals(1, dao.getAll().size)
        assertEquals("Ambient Flight", dao.getAll().first().title)
    }

    @Test
    fun `resolve video url re extracts when stream is near expiry`() = runTest {
        mockkObject(NewPipeHelper)
        val now = System.currentTimeMillis()
        val dao =
            FakeYouTubeCacheDao().apply {
                insertAll(
                    listOf(
                        YouTubeCacheEntity(
                            videoId = "abc123",
                            videoPageUrl = "https://www.youtube.com/watch?v=abc123",
                            streamUrl = "https://cdn.example.com/old.mpd",
                            title = "Ambient Flight",
                            streamUrlExpiresAt = now + 1_000L,
                            searchCachedAt = now,
                        ),
                    ),
                )
            }
        val prefs = createPreferences()
        val repository = YouTubeSourceRepository(dao, prefs)

        coEvery { NewPipeHelper.extractStreamUrl("https://www.youtube.com/watch?v=abc123", "1080p", false) } returns
            "https://cdn.example.com/new.mpd"

        val result = repository.resolveVideoUrl("https://www.youtube.com/watch?v=abc123")

        assertEquals("https://cdn.example.com/new.mpd", result)
        assertEquals("https://cdn.example.com/new.mpd", dao.getAll().first().streamUrl)
    }

    @Test
    fun `resolve video url extracts directly when cache entry is missing`() = runTest {
        mockkObject(NewPipeHelper)
        val dao = FakeYouTubeCacheDao()
        val prefs = createPreferences()
        val repository = YouTubeSourceRepository(dao, prefs)

        coEvery { NewPipeHelper.extractStreamUrl("https://www.youtube.com/watch?v=missing1", "1080p", false) } returns
            "https://cdn.example.com/missing1.mpd"

        val result = repository.resolveVideoUrl("https://www.youtube.com/watch?v=missing1")

        assertEquals("https://cdn.example.com/missing1.mpd", result)
        assertEquals(1, dao.getAll().size)
        coVerify(exactly = 0) { NewPipeHelper.searchVideos(any(), any()) }
    }

    @Test
    fun `preload video url does not record playback history`() = runTest {
        val now = System.currentTimeMillis()
        val prefs = createPreferences()
        val dao =
            FakeYouTubeCacheDao().apply {
                insertAll(
                    listOf(
                        YouTubeCacheEntity(
                            videoId = "preload1",
                            videoPageUrl = "https://www.youtube.com/watch?v=preload1",
                            streamUrl = "https://cdn.example.com/preload1.mpd",
                            title = "Preload Video",
                            streamUrlExpiresAt = now + (2L * 60L * 60L * 1000L),
                            searchCachedAt = now,
                        ),
                    ),
                )
            }
        val repository = YouTubeSourceRepository(dao, prefs)

        val result = repository.preloadVideoUrl("https://www.youtube.com/watch?v=preload1")

        assertEquals("https://cdn.example.com/preload1.mpd", result)
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `force refresh aggregates multiple queries into a large cache`() = runTest {
        mockkObject(NewPipeHelper)
        val dao = FakeYouTubeCacheDao()
        val prefs = createPreferences()
        val repository = YouTubeSourceRepository(dao, prefs)

        coEvery { NewPipeHelper.searchVideos(any(), any()) } answers {
            val query = firstArg<String>().replace("\\s+".toRegex(), "_")
            (1..20).map { index ->
                searchItem("${query}_$index", "Video $query $index")
            }
        }
        coEvery { NewPipeHelper.extractStreamUrl(any(), "1080p", false) } answers {
            val url = firstArg<String>()
            "https://cdn.example.com/${url.substringAfter("v=")}.mpd"
        }

        val refreshedCount = repository.forceRefresh()

        assertTrue(refreshedCount in 180..200)
        assertEquals(refreshedCount, dao.getAll().size)
    }

    @Test
    fun `force refresh round robins across search variants`() = runTest {
        mockkObject(NewPipeHelper)
        val dao = FakeYouTubeCacheDao()
        val prefs = createPreferences(query = "ambient aerial")
        val repository = YouTubeSourceRepository(dao, prefs)

        coEvery { NewPipeHelper.searchVideos(any(), any()) } answers {
            val queryKey = firstArg<String>().replace("\\s+".toRegex(), "_")
            listOf(
                searchItem("${queryKey}_1", "Video $queryKey 1"),
                searchItem("${queryKey}_2", "Video $queryKey 2"),
            )
        }
        coEvery { NewPipeHelper.extractStreamUrl(any(), "1080p", false) } answers {
            val url = firstArg<String>()
            "https://cdn.example.com/${url.substringAfter("v=")}.mpd"
        }

        repository.forceRefresh()

        val firstSixVariantKeys =
            dao.getAll()
                .take(6)
                .map { it.videoId.substringBeforeLast('_') }
                .toSet()

        assertTrue(firstSixVariantKeys.size >= 3)
    }

    @Test
    fun `warm cache falls back to cached data when refresh fails`() = runTest {
        mockkObject(NewPipeHelper)
        val cachedEntry =
            YouTubeCacheEntity(
                videoId = "cached1",
                videoPageUrl = "https://www.youtube.com/watch?v=cached1",
                streamUrl = "https://cdn.example.com/cached.mpd",
                title = "Cached Video",
                streamUrlExpiresAt = System.currentTimeMillis() + (2L * 60L * 60L * 1000L),
                searchCachedAt = System.currentTimeMillis(),
            )
        val dao =
            FakeYouTubeCacheDao().apply {
                insertAll(listOf(cachedEntry))
            }
        val prefs = createPreferences(cacheVersion = 0)
        val repository = YouTubeSourceRepository(dao, prefs)

        coEvery { NewPipeHelper.searchVideos(any(), any()) } throws YouTubeSourceException("offline")

        val result = repository.warmCache(forceSearchRefresh = true)

        assertEquals(1, result)
        assertTrue(dao.getAll().isNotEmpty())
        assertEquals("https://cdn.example.com/cached.mpd", dao.getAll().first().streamUrl)
    }

    private fun createPreferences(
        query: String = YouTubeSourceRepository.DEFAULT_QUERY,
        quality: String = YouTubeSourceRepository.DEFAULT_QUALITY,
        minDurationMinutes: Int = YouTubeSourceRepository.DEFAULT_MIN_DURATION_MINUTES,
        shuffle: Boolean = false,
        cacheVersion: Int = 22,
        cacheSignature: String =
            "${QueryFormulaEngine.freshnessSeed(query)}|$minDurationMinutes|$quality|muxed|" +
                QueryFormulaEngine.categorySignature(QueryFormulaEngine.CategoryPreferences()),
        muteVideos: Boolean = false,
    ): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { prefs.getString(YouTubeSourceRepository.KEY_QUERY, any()) } returns query
        every { prefs.getString(YouTubeSourceRepository.KEY_QUALITY, any()) } returns quality
        every { prefs.getString(YouTubeSourceRepository.KEY_CACHE_SIGNATURE, any()) } returns cacheSignature
        every { prefs.getString(YouTubeSourceRepository.KEY_PLAY_HISTORY, any()) } returns ""
        every { prefs.getString(YouTubeSourceRepository.KEY_LAST_CATEGORY, any()) } returns ""
        every { prefs.getString(YouTubeSourceRepository.KEY_THEME_HISTORY, any()) } returns ""
        every { prefs.getString(YouTubeSourceRepository.KEY_LAST_CHANNEL, any()) } returns ""
        every { prefs.getString(YouTubeSourceRepository.KEY_RECENT_REFRESH_IDS, any()) } returns ""
        every { prefs.getInt(YouTubeSourceRepository.KEY_MIN_DURATION, any()) } returns minDurationMinutes
        every { prefs.getInt(YouTubeSourceRepository.KEY_CACHE_VERSION, any()) } returns cacheVersion
        every { prefs.getInt(YouTubeSourceRepository.KEY_FIRST_LAUNCH_INDEX, any()) } returns 0
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_SHUFFLE, any()) } returns shuffle
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_FIRST_LAUNCH, any()) } returns false
        every { prefs.getBoolean("mute_videos", any()) } returns muteVideos
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_NATURE, any()) } returns true
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_ANIMALS, any()) } returns true
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_DRONE, any()) } returns true
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_CITIES, any()) } returns false
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_SPACE, any()) } returns true
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_OCEAN, any()) } returns true
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_WEATHER, any()) } returns false
        every { prefs.getBoolean(YouTubeSourceRepository.KEY_CATEGORY_WINTER, any()) } returns true
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } just Runs

        return prefs
    }

    private fun searchItem(
        videoId: String,
        title: String,
    ): StreamInfoItem {
        val item = mockk<StreamInfoItem>()
        every { item.getDuration() } returns 1_200L
        every { item.getUrl() } returns "https://www.youtube.com/watch?v=$videoId"
        every { item.getName() } returns title
        every { item.getUploaderName() } returns "Channel $videoId"
        return item
    }

    private class FakeYouTubeCacheDao : YouTubeCacheDao {
        private val entries = linkedMapOf<String, YouTubeCacheEntity>()

        override fun getAll(): List<YouTubeCacheEntity> = entries.values.toList()

        override fun getValidEntries(now: Long): List<YouTubeCacheEntity> =
            entries.values.filter { it.streamUrlExpiresAt > now }

        override fun insertAll(entries: List<YouTubeCacheEntity>) {
            entries.forEach { entry ->
                this.entries[entry.videoId] = entry
            }
        }

        override fun clearAll() {
            entries.clear()
        }

        override fun updateStreamUrl(
            videoId: String,
            newUrl: String,
            newExpiresAt: Long,
        ) {
            val existing = entries[videoId] ?: return
            entries[videoId] =
                existing.copy(
                    streamUrl = newUrl,
                    streamUrlExpiresAt = newExpiresAt,
                )
        }

        override fun getOldestCachedAt(): Long? =
            entries.values.minOfOrNull { it.searchCachedAt }

        override fun getByVideoPageUrl(videoPageUrl: String): YouTubeCacheEntity? =
            entries.values.firstOrNull { it.videoPageUrl == videoPageUrl }

        override fun markAsBad(videoId: String) {
            val existing = entries[videoId] ?: return
            entries[videoId] = existing.copy(isBad = true)
        }

        override fun markAsPlayed(
            videoId: String,
            timestamp: Long,
        ) {
            val existing = entries[videoId] ?: return
            entries[videoId] = existing.copy(lastPlayedAt = timestamp)
        }

        override fun resetPlayHistory() {
            entries.replaceAll { _, entry -> entry.copy(lastPlayedAt = 0L) }
        }

        override fun getUnwatchedEntry(cutoff: Long): YouTubeCacheEntity? =
            entries.values
                .filter { !it.isBad && (it.lastPlayedAt == 0L || it.lastPlayedAt < cutoff) }
                .randomOrNull()

        override fun getLeastRecentlyPlayed(): YouTubeCacheEntity? =
            entries.values
                .filterNot { it.isBad }
                .minByOrNull { it.lastPlayedAt }
    }
}
