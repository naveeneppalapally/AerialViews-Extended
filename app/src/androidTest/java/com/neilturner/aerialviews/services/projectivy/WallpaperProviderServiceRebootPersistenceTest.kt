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
import com.neilturner.aerialviews.testing.YouTubeInstrumentationFixtures
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService

@RunWith(AndroidJUnit4::class)
class WallpaperProviderServiceRebootPersistenceTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var database: YouTubeCacheDatabase
    private var serviceConnection: ServiceConnection? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        database = YouTubeCacheDatabase.getInstance(context)
    }

    @After
    fun tearDown() {
        unbindWallpaperService()
        stopWallpaperService()
    }

    @Test
    fun primeWallpaperHistoryForRebootValidation() {
        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
        YouTubeInstrumentationFixtures.configureProjectivyForYouTubeOnly(
            context = context,
            prefs = prefs,
            entryCount = 200,
        )
        YouTubeInstrumentationFixtures.seedProjectivyYouTubeCache(database, entryCount = 200)

        val service = bindWallpaperService()
        val firstWallpaper = service.getWallpapers(Event.TimeElapsed()).first().uri

        prefs.edit {
            putString(KEY_TEST_LAST_URI, firstWallpaper)
            putBoolean(KEY_TEST_REBOOT_PRIMED, true)
        }

        assertTrue("Expected Projectivy reboot validation state to be primed", prefs.getBoolean(KEY_TEST_REBOOT_PRIMED, false))
    }

    @Test
    fun keepsProjectivyNoveltyAfterEmulatorReboot() {
        assertTrue(
            "Expected reboot validation state to be primed before verification",
            prefs.getBoolean(KEY_TEST_REBOOT_PRIMED, false),
        )
        val lastWallpaperBeforeReboot = prefs.getString(KEY_TEST_LAST_URI, null).orEmpty()
        assertTrue("Expected primed wallpaper URI before reboot validation", lastWallpaperBeforeReboot.isNotBlank())

        val service = bindWallpaperService()
        val firstWallpaperAfterReboot = service.getWallpapers(Event.TimeElapsed()).first().uri

        assertNotEquals(
            "Expected Projectivy reboot to preserve wallpaper novelty across process death",
            lastWallpaperBeforeReboot,
            firstWallpaperAfterReboot,
        )

        YouTubeInstrumentationFixtures.resetAppState(database, prefs)
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

    private companion object {
        const val KEY_TEST_LAST_URI = "projectivy_test_reboot_last_uri"
        const val KEY_TEST_REBOOT_PRIMED = "projectivy_test_reboot_primed"
    }
}