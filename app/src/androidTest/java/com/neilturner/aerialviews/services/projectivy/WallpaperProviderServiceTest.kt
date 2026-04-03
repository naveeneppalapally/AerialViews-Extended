package com.neilturner.aerialviews.services.projectivy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neilturner.aerialviews.providers.youtube.YouTubeCacheDatabase
import com.neilturner.aerialviews.providers.youtube.YouTubeCacheEntity
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository
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
        database.clearAllTables()
        clearProjectivyState()
        seedYouTubeCache(entryCount = 200)
        configureProjectivyForYouTubeOnly()
    }

    @After
    fun tearDown() {
        unbindWallpaperService()
        stopWallpaperService()
        database.clearAllTables()
        clearProjectivyState()
    }

    @Test
    fun hiddenLauncherReturnsNoWallpapers() {
        val service = bindWallpaperService()

        val wallpapers = service.getWallpapers(Event.LauncherIdleModeChanged(isIdle = true))

        assertTrue("Expected no wallpapers while Projectivy is hidden", wallpapers.isEmpty())
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

    private fun stopWallpaperService() {
        context.stopService(Intent(context, WallpaperProviderService::class.java))
    }

    private fun clearProjectivyState() {
        prefs.edit {
            remove("projectivy_shared_providers")
            remove("projectivy_shuffle_videos")
            remove("projectivy_served_wallpaper_history")
            remove("projectivy_served_rotation_cursor")
            remove("yt_enabled")
            remove("yt_shuffle")
            remove("yt_quality")
            remove(YouTubeSourceRepository.KEY_CACHE_VERSION)
            remove(YouTubeSourceRepository.KEY_CACHE_SIGNATURE)
            remove(YouTubeSourceRepository.KEY_FIRST_LAUNCH)
            remove(YouTubeSourceRepository.KEY_FIRST_LAUNCH_INDEX)
            remove(YouTubeSourceRepository.KEY_COUNT)
        }
    }

    private fun configureProjectivyForYouTubeOnly() {
        prefs.edit {
            putStringSet("projectivy_shared_providers", setOf("youtube"))
            putBoolean("projectivy_shuffle_videos", false)
            putBoolean("yt_enabled", true)
            putBoolean("yt_shuffle", false)
            putString("yt_quality", "1080p")
            putInt(YouTubeSourceRepository.KEY_CACHE_VERSION, currentCacheVersion())
            putString(YouTubeSourceRepository.KEY_CACHE_SIGNATURE, currentCacheSignature())
            putBoolean(YouTubeSourceRepository.KEY_FIRST_LAUNCH, false)
            putInt(YouTubeSourceRepository.KEY_FIRST_LAUNCH_INDEX, 0)
            putString(YouTubeSourceRepository.KEY_COUNT, "200")
        }
    }

    private fun seedYouTubeCache(entryCount: Int) {
        val now = System.currentTimeMillis()
        val entries =
            (1..entryCount).map { index ->
                YouTubeCacheEntity(
                    videoId = "video$index",
                    videoPageUrl = "https://www.youtube.com/watch?v=video$index",
                    streamUrl = "https://rr1---sn.example.googlevideo.com/videoplayback?id=video$index.mp4&itag=137",
                    title = "Ambient Projectivy Video $index",
                    uploaderName = "channel$index",
                    durationSeconds = 600,
                    categoryKey = "nature",
                    streamUrlExpiresAt = now + 86_400_000L,
                    searchCachedAt = now,
                    searchQuery = "4K ambient nature",
                    isBad = false,
                    lastPlayedAt = 0L,
                )
            }

        database.youtubeCacheDao().insertAll(entries)
    }

    private fun currentCacheSignature(): String {
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        return "$versionCode|v${currentCacheVersion()}"
    }

    private fun currentCacheVersion(): Int {
        runCatching {
            val field = YouTubeSourceRepository::class.java.getDeclaredField("CURRENT_CACHE_VERSION")
            field.isAccessible = true
            return field.getInt(null)
        }
        val companionClass =
            YouTubeSourceRepository::class.java.declaredClasses.firstOrNull { declaredClass ->
                declaredClass.simpleName == "Companion"
            } ?: error("Unable to locate YouTubeSourceRepository companion")
        val field = companionClass.getDeclaredField("CURRENT_CACHE_VERSION")
        field.isAccessible = true
        return field.getInt(null)
    }
}