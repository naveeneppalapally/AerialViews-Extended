package com.neilturner.aerialviews.services.projectivy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neilturner.aerialviews.providers.youtube.YouTubeCacheDatabase
import com.neilturner.aerialviews.testing.YouTubeInstrumentationFixtures
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService

@RunWith(AndroidJUnit4::class)
class WallpaperProviderServiceTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var database: YouTubeCacheDatabase
    private var serviceConnection: ServiceConnection? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        database = YouTubeCacheDatabase.getInstance(context)

        stopWallpaperService()
        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(database, entryCount = 200)
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 200,
        )
    }

    @After
    fun tearDown() {
        unbindWallpaperService()
        stopWallpaperService()
        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
    }

    @Test
    fun nullEventReturnsWallpapers() {
        val service = bindWallpaperService()

        val wallpapers = service.getWallpapers(null)

        assertFalse("Expected wallpapers when Projectivy requests an initial snapshot", wallpapers.isEmpty())
    }

    @Test
    fun launcherIdleModeChangedReturnsWallpapers() {
        val service = bindWallpaperService()

        val wallpapers = service.getWallpapers(Event.LauncherIdleModeChanged(isIdle = true))

        assertFalse("Expected wallpapers when Projectivy idle mode changes", wallpapers.isEmpty())
    }

    @Test
    fun youtubeLimitModeAppliesPlaybackCapToProjectivyUri() {
        prefs.edit()
            .putString("yt_playback_length_mode", "limit")
            .putString("yt_playback_max_minutes", "5")
            .apply()

        val service = bindWallpaperService()

        val firstUri = service.getWallpapers(Event.TimeElapsed()).first().uri

        assertTrue(
            "Expected Projectivy YouTube limit mode to cap playback length, got $firstUri",
            firstUri.contains("#t=30,300"),
        )
    }

    @Test
    fun youtubeFullModeKeepsIntroSkipWithoutPlaybackCap() {
        prefs.edit()
            .putString("yt_playback_length_mode", "full")
            .putString("yt_playback_max_minutes", "5")
            .apply()

        val service = bindWallpaperService()

        val firstUri = service.getWallpapers(Event.TimeElapsed()).first().uri

        assertTrue(
            "Expected Projectivy YouTube full mode to retain intro skip, got $firstUri",
            firstUri.contains("#t=30"),
        )
        assertFalse(
            "Expected Projectivy YouTube full mode to avoid an explicit playback cap, got $firstUri",
            firstUri.contains("#t=30,"),
        )
    }

    @Test
    fun youtubeSegmentModeReturnsBoundedSegmentWindow() {
        prefs.edit()
            .putString("yt_playback_length_mode", "segment")
            .putString("yt_playback_max_minutes", "5")
            .apply()

        val service = bindWallpaperService()

        val firstUri = service.getWallpapers(Event.TimeElapsed()).first().uri
        val (startSeconds, endSeconds) = extractTimeWindow(firstUri)

        assertTrue(
            "Expected Projectivy YouTube segment mode to start after intro skip, got $firstUri",
            startSeconds >= 30L,
        )
        assertEquals(
            "Expected Projectivy YouTube segment mode to keep a bounded playback window after intro skip, got $firstUri",
            270L,
            (endSeconds ?: 0L) - startSeconds,
        )
        assertTrue(
            "Expected Projectivy YouTube segment mode to stay within known duration, got $firstUri",
            (endSeconds ?: 0L) <= 600L,
        )
    }

    @Test
    fun youtubeProjectivyUrisUseDirectPlayableStreams() {
        val service = bindWallpaperService()

        val wallpapers = service.getWallpapers(Event.TimeElapsed())

        assertFalse("Expected Projectivy to return YouTube wallpapers", wallpapers.isEmpty())
        assertTrue(
            "Expected Projectivy YouTube URIs to be direct playable streams, got ${wallpapers.take(5).map { it.uri }}",
            wallpapers.take(5).all { wallpaper ->
                val uri = wallpaper.uri.substringBefore('#')
                !uri.contains("youtube.com/watch") &&
                    !uri.contains("youtu.be/") &&
                    !uri.contains("/manifest/") &&
                    !uri.contains(".mpd") &&
                    !uri.contains(".m3u8")
            },
        )
    }

    @Test
    fun remembersServedWallpaperAcrossServiceRelaunch() {
        val firstService = bindWallpaperService()
        val firstWallpaper = firstService.getWallpapers(Event.TimeElapsed()).first().uri

        unbindWallpaperService()
        stopWallpaperService()

        val secondService = bindWallpaperService()
        val secondWallpaper = secondService.getWallpapers(Event.TimeElapsed()).first().uri

        assertNotEquals(
            "Expected Projectivy relaunch to avoid serving the same first wallpaper again",
            firstWallpaper,
            secondWallpaper,
        )
    }

    @Test
    fun servesTwoHundredUniqueYouTubeWallpapersWithoutRepeating() {
        val service = bindWallpaperService()
        val servedUris = mutableListOf<String>()

        repeat(200) { index ->
            if (index > 0 && index % 40 == 0) {
                Thread.sleep(5_200L)
            }

            val wallpapers = service.getWallpapers(Event.TimeElapsed())
            assertFalse("Expected wallpapers for request $index", wallpapers.isEmpty())
            servedUris += wallpapers.first().uri
        }

        assertTrue(
            "Expected no consecutive repeats, got $servedUris",
            servedUris.zipWithNext().all { (first, second) -> first != second },
        )
        assertEquals(
            "Expected 200 unique first wallpapers across 200 requests",
            200,
            servedUris.distinct().size,
        )
    }

    @Test
    fun cachedWallpaperResponsesStayFast() {
        val service = bindWallpaperService()
        val firstDurationMs = measureTimeMillis {
            assertFalse(service.getWallpapers(Event.TimeElapsed()).isEmpty())
        }
        val cachedDurationsMs =
            buildList {
                repeat(5) {
                    add(
                        measureTimeMillis {
                            assertFalse(service.getWallpapers(Event.TimeElapsed()).isEmpty())
                        },
                    )
                }
            }

        assertTrue(
            "Expected cached Projectivy calls to stay under 1000ms, first=${firstDurationMs}ms cached=${cachedDurationsMs}ms",
            cachedDurationsMs.maxOrNull() ?: Long.MAX_VALUE < 1_000L,
        )
    }

    @Test
    fun servesFiveBatchesOfTwoHundredWithoutRepeatingAcrossBatches() {
        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(database, entryCount = 1_000)
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 1_000,
        )

        var service = bindWallpaperService()
        val servedUris = mutableListOf<String>()
        val durationsMs = mutableListOf<Long>()

        repeat(1_000) { index ->
            if (index > 0 && index % 40 == 0) {
                unbindWallpaperService()
                stopWallpaperService()
                service = bindWallpaperService()
            }

            val durationMs =
                measureTimeMillis {
                    val wallpapers = service.getWallpapers(Event.TimeElapsed())
                    assertFalse("Expected wallpapers for request $index", wallpapers.isEmpty())
                    servedUris += wallpapers.first().uri
                }
            durationsMs += durationMs
        }

        val batches = servedUris.chunked(200)
        val previousBatchSets = mutableListOf<Set<String>>()
        val batchSummaries = mutableListOf<String>()

        batches.forEachIndexed { batchIndex, batch ->
            val batchSet = batch.toSet()
            val overlapSummary =
                previousBatchSets.mapIndexed { previousIndex, previousBatch ->
                    "b${previousIndex + 1}=${batchSet.intersect(previousBatch).size}"
                }
            val consecutiveRepeats = batch.zipWithNext().count { (first, second) -> first == second }
            batchSummaries +=
                "batch=${batchIndex + 1} unique=${batchSet.size} consecutiveRepeats=$consecutiveRepeats overlap=[${overlapSummary.joinToString()}]"

            assertEquals(
                "Expected batch ${batchIndex + 1} to contain 200 unique wallpapers. Summaries=$batchSummaries",
                200,
                batchSet.size,
            )
            assertEquals(
                "Expected batch ${batchIndex + 1} to avoid consecutive repeats. Summaries=$batchSummaries",
                0,
                consecutiveRepeats,
            )
            assertTrue(
                "Expected batch ${batchIndex + 1} to avoid overlaps with prior batches. Summaries=$batchSummaries",
                previousBatchSets.none { previousBatch -> batchSet.intersect(previousBatch).isNotEmpty() },
            )
            previousBatchSets += batchSet
        }

        val cacheBoundaryDurationsMs = durationsMs.filterIndexed { index, _ -> index == 0 || index % 40 == 0 }
        val cachedDurationsMs = durationsMs.filterIndexed { index, _ -> index > 0 && index % 40 != 0 }
        Log.i(
            TEST_TAG,
            "Projectivy 5x200 summaries=$batchSummaries boundaryDurationsMs=$cacheBoundaryDurationsMs cachedMaxMs=${cachedDurationsMs.maxOrNull() ?: -1L}",
        )
        assertTrue(
            "Expected cached Projectivy responses to remain under 1000ms during the 5x200 run. boundary=$cacheBoundaryDurationsMs cached=$cachedDurationsMs summaries=$batchSummaries",
            cachedDurationsMs.maxOrNull() ?: Long.MAX_VALUE < 1_000L,
        )
    }

    private companion object {
        const val TEST_TAG = "WallpaperProviderServiceTest"
    }

    private fun bindWallpaperService(): IWallpaperProviderService {
        unbindWallpaperService()

        val connectionLatch = CountDownLatch(1)
        var wallpaperService: IWallpaperProviderService? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    wallpaperService = IWallpaperProviderService.Stub.asInterface(service)
                    connectionLatch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    wallpaperService = null
                }
            }

        val bound =
            context.bindService(
                Intent(context, WallpaperProviderService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        assertTrue("Expected WallpaperProviderService bind to succeed", bound)
        assertTrue(
            "Timed out waiting for WallpaperProviderService binding",
            connectionLatch.await(10, TimeUnit.SECONDS),
        )

        serviceConnection = connection
        return wallpaperService ?: error("WallpaperProviderService binder was null")
    }

    private fun unbindWallpaperService() {
        val connection = serviceConnection ?: return
        context.unbindService(connection)
        serviceConnection = null
    }

    private fun extractTimeWindow(uri: String): Pair<Long, Long?> {
        val timeFragment =
            uri.substringAfter('#', missingDelimiterValue = "")
                .split('&')
                .firstOrNull { fragment -> fragment.startsWith("t=") }
                ?.removePrefix("t=")
                ?: error("Expected Projectivy YouTube URI to contain a time fragment: $uri")

        val values = timeFragment.split(',')
        val startSeconds = values.first().toLong()
        val endSeconds = values.getOrNull(1)?.toLong()
        return startSeconds to endSeconds
    }

    private fun stopWallpaperService() {
        context.stopService(Intent(context, WallpaperProviderService::class.java))
    }

}