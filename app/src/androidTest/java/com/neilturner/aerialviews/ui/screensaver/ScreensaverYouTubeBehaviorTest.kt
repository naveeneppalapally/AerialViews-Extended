package com.neilturner.aerialviews.ui.screensaver

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neilturner.aerialviews.providers.youtube.YouTubeCacheDatabase
import com.neilturner.aerialviews.testing.YouTubeInstrumentationFixtures
import kotlin.math.roundToLong
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

@RunWith(AndroidJUnit4::class)
class ScreensaverYouTubeBehaviorTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var database: YouTubeCacheDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        database = YouTubeCacheDatabase.getInstance(context)

        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
        YouTubeInstrumentationFixtures.configureYouTubeOnlyPlayback(
            context = context,
            prefs = prefs,
            entryCount = 24,
            quality = "1080p",
        )
        YouTubeInstrumentationFixtures.seedPlayableYouTubeCache(database, entryCount = 24)
    }

    @After
    fun tearDown() {
        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
    }

    @Test
    fun dpadRightAdvancesToDifferentYouTubeVideoWithinReasonableTime() {
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            val initialSnapshot = waitForSnapshot(
                scenario = scenario,
                timeoutMs = 30_000L,
            ) { snapshot ->
                snapshot.canSkip && snapshot.videoId.isNotBlank()
            }

            val latenciesMs = mutableListOf<Long>()
            val transitions = mutableListOf<String>()
            var previousVideoId = initialSnapshot.videoId

            repeat(10) {
                val startedAt = SystemClock.elapsedRealtime()
                dispatchNextKey(scenario)
                val nextSnapshot = waitForSnapshot(
                    scenario = scenario,
                    timeoutMs = 10_000L,
                ) { snapshot ->
                    snapshot.canSkip && snapshot.videoId.isNotBlank() && snapshot.videoId != previousVideoId
                }

                latenciesMs += SystemClock.elapsedRealtime() - startedAt
                transitions += "$previousVideoId -> ${nextSnapshot.videoId}"
                previousVideoId = nextSnapshot.videoId
            }

            val sortedLatencies = latenciesMs.sorted()
            val medianLatencyMs =
                if (sortedLatencies.isEmpty()) {
                    0L
                } else {
                    val middle = sortedLatencies.size / 2
                    if (sortedLatencies.size % 2 == 0) {
                        ((sortedLatencies[middle - 1] + sortedLatencies[middle]) / 2.0).roundToLong()
                    } else {
                        sortedLatencies[middle]
                    }
                }
            val maxLatencyMs = latenciesMs.maxOrNull() ?: 0L

            Timber.i(
                "Screensaver next-button timings ms=%s median=%s max=%s transitions=%s",
                latenciesMs,
                medianLatencyMs,
                maxLatencyMs,
                transitions,
            )

            assertTrue("Expected all skip transitions to stay under 8000ms, got $latenciesMs", maxLatencyMs < 8_000L)
            assertTrue("Expected median skip latency under 4000ms, got $medianLatencyMs ms", medianLatencyMs < 4_000L)
        }
    }

    private fun dispatchNextKey(scenario: ActivityScenario<TestActivity>) {
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
    }

    private fun waitForSnapshot(
        scenario: ActivityScenario<TestActivity>,
        timeoutMs: Long,
        predicate: (ControllerSnapshot) -> Boolean,
    ): ControllerSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastSnapshot = ControllerSnapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            lastSnapshot = currentSnapshot(scenario)
            if (predicate(lastSnapshot)) {
                return lastSnapshot
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for screensaver snapshot. Last snapshot=$lastSnapshot")
    }

    private fun currentSnapshot(scenario: ActivityScenario<TestActivity>): ControllerSnapshot {
        var snapshot = ControllerSnapshot()
        scenario.onActivity { activity ->
            val controller = runCatching { getFieldValue<Any?>(activity, "screenController") }.getOrNull()
            if (controller == null) {
                snapshot = ControllerSnapshot()
                return@onActivity
            }

            val canSkip = runCatching { getFieldValue<Boolean>(controller, "canSkip") }.getOrDefault(false)
            val currentMedia = runCatching { getFieldValue<Any?>(controller, "currentMedia") }.getOrNull()
            val currentUri = currentMedia?.let { media -> runCatching { media.javaClass.getMethod("getUri").invoke(media)?.toString().orEmpty() }.getOrDefault("") }.orEmpty()
            val videoId =
                currentMedia?.let { media ->
                    runCatching {
                        val metadata = media.javaClass.getMethod("getMetadata").invoke(media)
                        val exif = metadata?.javaClass?.getMethod("getExif")?.invoke(metadata)
                        exif?.javaClass?.getMethod("getDescription")?.invoke(exif)?.toString().orEmpty()
                    }.getOrDefault("")
                }.orEmpty()
            snapshot = ControllerSnapshot(canSkip = canSkip, videoId = videoId, uri = currentUri)
        }
        return snapshot
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getFieldValue(
        instance: Any,
        fieldName: String,
    ): T {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            val field = runCatching { currentClass.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(instance) as T
            }
            currentClass = currentClass.superclass
        }
        error("Unable to find field $fieldName on ${instance.javaClass.name}")
    }

    private data class ControllerSnapshot(
        val canSkip: Boolean = false,
        val videoId: String = "",
        val uri: String = "",
    )
}