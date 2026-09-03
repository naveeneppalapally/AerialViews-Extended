package com.neilturner.aerialviews.services.projectivy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.net.Uri
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
        YouTubeInstrumentationFixtures.resetAppState(context, database, prefs)
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
        YouTubeInstrumentationFixtures.resetAppState(context, database, prefs)
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

        val firstUri = service.getWallpapers(Event.TimeElapsed).first().uri
        val (startSeconds, endSeconds) = extractTimeWindow(firstUri)

        assertEquals(
            "Expected Projectivy YouTube limit mode to keep the intro skip start, got $firstUri",
            30L,
            startSeconds,
        )
        assertEquals(
            "Expected Projectivy YouTube limit mode to cap playback length, got $firstUri",
            300L,
            endSeconds,
        )
    }

    @Test
    fun youtubeFullModeKeepsIntroSkipWithoutPlaybackCap() {
        prefs.edit()
            .putString("yt_playback_length_mode", "full")
            .putString("yt_playback_max_minutes", "5")
            .apply()

        val service = bindWallpaperService()

        val firstUri = service.getWallpapers(Event.TimeElapsed).first().uri
        val (startSeconds, endSeconds) = extractTimeWindow(firstUri)

        assertEquals(
            "Expected Projectivy YouTube full mode to retain intro skip, got $firstUri",
            30L,
            startSeconds,
        )
        assertEquals(
            "Expected Projectivy YouTube full mode to avoid an explicit playback cap, got $firstUri",
            null,
            endSeconds,
        )
    }

    @Test
    fun youtubeSegmentModeReturnsBoundedSegmentWindow() {
        prefs.edit()
            .putString("yt_playback_length_mode", "segment")
            .putString("yt_playback_max_minutes", "5")
            .apply()

        val service = bindWallpaperService()

        val firstUri = service.getWallpapers(Event.TimeElapsed).first().uri
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

        val wallpapers = service.getWallpapers(Event.TimeElapsed)

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
    fun returnsMultipleWallpapersPerProjectivySnapshot() {
        val service = bindWallpaperService()

        val wallpapers = service.getWallpapers(Event.TimeElapsed)

        assertTrue(
            "Expected Projectivy snapshots to expose multiple wallpapers so the launcher can rotate them, got ${wallpapers.size}",
            wallpapers.size > 1,
        )
    }

    @Test
    fun projectivyUhdPreferencePreservesUhdDirectStreams() {
        YouTubeInstrumentationFixtures.resetAppState(context, database, prefs)
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(
            database = database,
            entryCount = 40,
            itag = 266,
            qualityLabel = "2160p",
        )
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 40,
            quality = "2160p",
        )

        val service = bindWallpaperService()
        val wallpapers = service.getWallpapers(Event.TimeElapsed)
        val sampledWallpapers = wallpapers.take(5)
        val sampledItags =
            sampledWallpapers.mapNotNull { wallpaper ->
                Uri.parse(wallpaper.uri.substringBefore('#')).getQueryParameter("itag")?.toIntOrNull()
            }

        Log.i(
            TEST_TAG,
            "Projectivy UHD sample count=${sampledWallpapers.size} itags=$sampledItags uris=${sampledWallpapers.map { it.uri }}",
        )

        assertTrue("Expected Projectivy to return UHD wallpapers", sampledWallpapers.isNotEmpty())
        assertEquals(
            "Expected Projectivy UHD mode to preserve 2160p direct streams",
            listOf(266, 266, 266, 266, 266).take(sampledItags.size),
            sampledItags,
        )
    }

    @Test
    fun projectivyMaintainsQualityAcrossTwoHundredEntryCacheRounds() {
        val expectedItag = 266
        val expectedQualityLabel = "2160p"

        YouTubeInstrumentationFixtures.resetAppState(context, database, prefs)
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(
            database = database,
            entryCount = 200,
            itag = expectedItag,
            qualityLabel = expectedQualityLabel,
            videoIdPrefix = "projectivy-round1-",
        )
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 200,
            quality = expectedQualityLabel,
        )

        val firstRoundSummaries = runProjectivyQualityRound(
            roundLabel = "round1",
            expectedItag = expectedItag,
            expectedQualityLabel = expectedQualityLabel,
        )

        unbindWallpaperService()
        stopWallpaperService()

        YouTubeInstrumentationFixtures.resetAppState(context, database, prefs)
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(
            database = database,
            entryCount = 200,
            itag = expectedItag,
            qualityLabel = expectedQualityLabel,
            videoIdPrefix = "projectivy-round2-",
        )
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 200,
            quality = expectedQualityLabel,
        )

        val secondRoundSummaries = runProjectivyQualityRound(
            roundLabel = "round2",
            expectedItag = expectedItag,
            expectedQualityLabel = expectedQualityLabel,
        )

        Log.i(
            TEST_TAG,
            "Projectivy 2x200 quality summaries first=$firstRoundSummaries second=$secondRoundSummaries",
        )
    }

    @Test
    fun remembersServedWallpaperAcrossServiceRelaunch() {
        val firstService = bindWallpaperService()
        val firstWallpaper = firstService.getWallpapers(Event.TimeElapsed).first().uri

        unbindWallpaperService()
        stopWallpaperService()

        val secondService = bindWallpaperService()
        val secondWallpaper = secondService.getWallpapers(Event.TimeElapsed).first().uri

        assertNotEquals(
            "Expected Projectivy relaunch to avoid serving the same first wallpaper again",
            firstWallpaper,
            secondWallpaper,
        )
    }

    @Test
    fun servesProjectivyDirectWindowWithoutRepeating() {
        val service = bindWallpaperService()
        val servedUris = mutableListOf<String>()

        repeat(PROJECTIVY_DIRECT_WINDOW_SIZE) { index ->
            val wallpapers = service.getWallpapers(Event.TimeElapsed)
            assertFalse("Expected wallpapers for request $index", wallpapers.isEmpty())
            servedUris += wallpapers.first().uri
        }

        assertTrue(
            "Expected no consecutive repeats, got $servedUris",
            servedUris.zipWithNext().all { (first, second) -> first != second },
        )
        assertEquals(
            "Expected the first Projectivy direct-response window to expose distinct first wallpapers",
            PROJECTIVY_DIRECT_WINDOW_SIZE,
            servedUris.distinct().size,
        )
    }

    @Test
    fun cachedWallpaperResponsesStayFast() {
        val service = bindWallpaperService()
        val firstDurationMs = measureTimeMillis {
            assertFalse(service.getWallpapers(Event.TimeElapsed).isEmpty())
        }
        val cachedDurationsMs =
            buildList {
                repeat(5) {
                    add(
                        measureTimeMillis {
                            assertFalse(service.getWallpapers(Event.TimeElapsed).isEmpty())
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
    fun staysFastAcrossFiveProjectivyRebuilds() {
        YouTubeInstrumentationFixtures.resetAppState(context, database, prefs)
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(database, entryCount = 1_000)
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 1_000,
        )

        var service = bindWallpaperService()
        val servedUris = mutableListOf<String>()
        val durationsMs = mutableListOf<Long>()

        repeat(PROJECTIVY_WINDOW_BATCH_COUNT * PROJECTIVY_DIRECT_WINDOW_SIZE) { index ->
            if (index > 0 && index % PROJECTIVY_DIRECT_WINDOW_SIZE == 0) {
                unbindWallpaperService()
                stopWallpaperService()
                service = bindWallpaperService()
            }

            val durationMs =
                measureTimeMillis {
                    val wallpapers = service.getWallpapers(Event.TimeElapsed)
                    assertFalse("Expected wallpapers for request $index", wallpapers.isEmpty())
                    servedUris += wallpapers.first().uri
                }
            durationsMs += durationMs
        }

        val batches = servedUris.chunked(PROJECTIVY_DIRECT_WINDOW_SIZE)
        val batchSummaries = mutableListOf<String>()

        batches.forEachIndexed { batchIndex, batch ->
            val batchSet = batch.toSet()
            val consecutiveRepeats = batch.zipWithNext().count { (first, second) -> first == second }
            batchSummaries +=
                "batch=${batchIndex + 1} unique=${batchSet.size} consecutiveRepeats=$consecutiveRepeats"

            assertTrue(
                "Expected batch ${batchIndex + 1} to keep rotating across at least two first-wallpaper choices after a rebuild. Summaries=$batchSummaries",
                batchSet.size >= 2,
            )
        }

        val cacheBoundaryDurationsMs = durationsMs.filterIndexed { index, _ -> index == 0 || index % PROJECTIVY_DIRECT_WINDOW_SIZE == 0 }
        val cachedDurationsMs = durationsMs.filterIndexed { index, _ -> index > 0 && index % PROJECTIVY_DIRECT_WINDOW_SIZE != 0 }
        Log.i(
            TEST_TAG,
            "Projectivy ${PROJECTIVY_WINDOW_BATCH_COUNT}x${PROJECTIVY_DIRECT_WINDOW_SIZE} summaries=$batchSummaries boundaryDurationsMs=$cacheBoundaryDurationsMs cachedMaxMs=${cachedDurationsMs.maxOrNull() ?: -1L}",
        )
        assertTrue(
            "Expected cached Projectivy responses to remain under 1000ms during the bounded rotation run. boundary=$cacheBoundaryDurationsMs cached=$cachedDurationsMs summaries=$batchSummaries",
            cachedDurationsMs.maxOrNull() ?: Long.MAX_VALUE < 1_000L,
        )
    }

    private companion object {
        const val TEST_TAG = "WallpaperProviderServiceTest"
        const val PROJECTIVY_DIRECT_WINDOW_SIZE = 12
        const val PROJECTIVY_WINDOW_BATCH_COUNT = 5
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

    private fun runProjectivyQualityRound(
        roundLabel: String,
        expectedItag: Int,
        expectedQualityLabel: String,
    ): List<String> {
        var service = bindWallpaperService()
        val batchSummaries = mutableListOf<String>()

        repeat(PROJECTIVY_WINDOW_BATCH_COUNT) { batchIndex ->
            if (batchIndex > 0) {
                unbindWallpaperService()
                stopWallpaperService()
                service = bindWallpaperService()
            }

            val wallpapers = service.getWallpapers(Event.TimeElapsed)
            assertEquals(
                "Expected Projectivy to return a full wallpaper batch for $roundLabel batch ${batchIndex + 1}",
                PROJECTIVY_DIRECT_WINDOW_SIZE,
                wallpapers.size,
            )

            val sampledWallpapers = wallpapers.take(PROJECTIVY_DIRECT_WINDOW_SIZE)
            val sampledItags = sampledWallpapers.map { extractStreamQueryParameterAsInt(it.uri, "itag") }
            val sampledQualityLabels = sampledWallpapers.map { extractStreamQueryParameter(it.uri, "quality_label") }

            assertEquals(
                "Expected Projectivy $roundLabel batch ${batchIndex + 1} to preserve 2160p itags",
                List(PROJECTIVY_DIRECT_WINDOW_SIZE) { expectedItag },
                sampledItags,
            )
            assertEquals(
                "Expected Projectivy $roundLabel batch ${batchIndex + 1} to preserve 2160p quality labels",
                List(PROJECTIVY_DIRECT_WINDOW_SIZE) { expectedQualityLabel },
                sampledQualityLabels,
            )

            batchSummaries +=
                "batch=${batchIndex + 1} first=${sampledWallpapers.first().uri} itags=${sampledItags.distinct()} qualities=${sampledQualityLabels.distinct()}"
        }

        return batchSummaries
    }

    private fun extractStreamQueryParameter(uri: String, key: String): String =
        Uri.parse(uri.substringBefore('#'))
            .getQueryParameter(key)
            ?: error("Expected Projectivy URI to contain $key: $uri")

    private fun extractStreamQueryParameterAsInt(uri: String, key: String): Int =
        extractStreamQueryParameter(uri, key).toIntOrNull()
            ?: error("Expected Projectivy URI query parameter $key to be an integer: $uri")

    private fun stopWallpaperService() {
        context.stopService(Intent(context, WallpaperProviderService::class.java))
    }

}