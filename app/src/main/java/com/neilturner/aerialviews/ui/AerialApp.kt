package com.neilturner.aerialviews.ui

import android.app.Application
import android.content.SharedPreferences
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import androidx.preference.PreferenceManager
import com.neilturner.aerialviews.models.prefs.AmazonVideoPrefs
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.enums.VideoQuality
import com.neilturner.aerialviews.models.prefs.AppleVideoPrefs
import com.neilturner.aerialviews.models.prefs.Comm1VideoPrefs
import com.neilturner.aerialviews.models.prefs.Comm2VideoPrefs
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyAmazonPrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyApplePrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyComm1Prefs
import com.neilturner.aerialviews.models.prefs.ProjectivyComm2Prefs
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.ui.helpers.DeviceHelper
import com.neilturner.aerialviews.utils.AppUpdateHelper
import com.neilturner.aerialviews.utils.LogcatCapture
import timber.log.Timber

class AerialApp : Application() {
    override fun onCreate() {
        super.onCreate()
        configureLogging()

        if (GeneralPrefs.enableLogCapture) {
            LogcatCapture.start(applicationContext)
        }

        if (BuildConfig.DEBUG || BuildConfig.FLAVOR.contains("beta", false)) {
            Timber.plant(Timber.DebugTree())
        }

        @Suppress("ControlFlowWithEmptyBody")
        if (BuildConfig.DEBUG) {
            // setupStrictMode()
        }

        initializeVideoQualityDefaults()

        if (!GeneralPrefs.checkForHevcSupport) {
            // FireTV Gen 1 and emulator can't play HEVC/H.265
            // Set video quality to H.264
            if (!DeviceHelper.hasHevcSupport()) changeVideoQuality()

            // Turn off location overlay as layout is broken on the phone
            if (!DeviceHelper.isTV(applicationContext)) changeOverlayOption()

            GeneralPrefs.checkForHevcSupport = true
        }

        initializeSourceModeDefaults()
        initializeProjectivyProviderDefaults()
        YouTubeFeature.initialize(this)
        AppUpdateHelper.refreshInBackground(this)
    }

    private fun initializeVideoQualityDefaults() {
        if (!DeviceHelper.isTV(applicationContext) ||
            !DeviceHelper.hasHevcSupport() ||
            !DeviceHelper.supportsUltraHdOutput(applicationContext)
        ) {
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        maybeSetDefaultQuality(prefs, "apple_videos_quality") {
            AppleVideoPrefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "amazon_videos_quality") {
            AmazonVideoPrefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "comm1_videos_quality") {
            Comm1VideoPrefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "comm2_videos_quality") {
            Comm2VideoPrefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "projectivy_apple_videos_quality") {
            ProjectivyApplePrefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "projectivy_amazon_videos_quality") {
            ProjectivyAmazonPrefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "projectivy_comm1_videos_quality") {
            ProjectivyComm1Prefs.quality = VideoQuality.VIDEO_4K_SDR
        }
        maybeSetDefaultQuality(prefs, "projectivy_comm2_videos_quality") {
            ProjectivyComm2Prefs.quality = VideoQuality.VIDEO_4K_SDR
        }
    }

    private fun maybeSetDefaultQuality(
        prefs: SharedPreferences,
        key: String,
        update: () -> Unit,
    ) {
        if (!prefs.contains(key)) {
            update()
        }
    }

    private fun initializeSourceModeDefaults() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.contains(KEY_SOURCE_MODE)) {
            prefs.edit().putString(KEY_SOURCE_MODE, SOURCE_MODE_COMBINED).apply()
            AppleVideoPrefs.enabled = true
            AmazonVideoPrefs.enabled = true
            Comm1VideoPrefs.enabled = true
            Comm2VideoPrefs.enabled = true
            YouTubeVideoPrefs.enabled = true
        }
    }

    private fun initializeProjectivyProviderDefaults() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.contains(KEY_PROJECTIVY_SHARED_PROVIDERS)) {
            prefs
                .edit()
                .putStringSet(
                    KEY_PROJECTIVY_SHARED_PROVIDERS,
                    setOf(
                        PROJECTIVY_PROVIDER_APPLE,
                        PROJECTIVY_PROVIDER_AMAZON,
                        PROJECTIVY_PROVIDER_COMM1,
                        PROJECTIVY_PROVIDER_COMM2,
                        PROJECTIVY_PROVIDER_YOUTUBE,
                    ),
                ).apply()
        }
        normalizeProjectivyProviderKeys()
    }

    private fun normalizeProjectivyProviderKeys() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rawProviders = prefs.getStringSet(KEY_PROJECTIVY_SHARED_PROVIDERS, null)?.toSet() ?: return
        val normalizedProviders =
            rawProviders.mapNotNull { provider ->
                when (provider.trim().lowercase()) {
                    "apple" -> "APPLE"
                    "amazon" -> "AMAZON"
                    "comm1" -> "COMM1"
                    "comm2" -> "COMM2"
                    "local" -> "LOCAL"
                    "youtube" -> "youtube"
                    else -> null
                }
            }.toSet()

        if (normalizedProviders.isEmpty()) {
            return
        }

        val migratedProviders =
            if (normalizedProviders == LEGACY_PROJECTIVY_DEFAULT_PROVIDERS) {
                PROJECTIVY_ALL_DEFAULT_PROVIDERS
            } else {
                normalizedProviders
            }

        if (migratedProviders != rawProviders) {
            prefs.edit().putStringSet(KEY_PROJECTIVY_SHARED_PROVIDERS, migratedProviders).apply()
        }
    }

    private fun configureLogging() {
        val debugLogging = BuildConfig.DEBUG

        // Current backend for SMBJ logs is slf4j-simple.
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true")
        System.setProperty("org.slf4j.simpleLogger.showLogName", "true")
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", if (debugLogging) "info" else "warn")
        System.setProperty("org.slf4j.simpleLogger.log.com.hierynomus", if (debugLogging) "debug" else "warn")
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.sshd", if (debugLogging) "debug" else "warn")

        // If Log4j is reintroduced as backend, keep status logging debug-only.
        System.setProperty("log4j2.debug", debugLogging.toString())
    }

    private fun changeVideoQuality() {
        AppleVideoPrefs.quality = VideoQuality.VIDEO_1080_H264
        Comm1VideoPrefs.quality = VideoQuality.VIDEO_1080_H264
        Comm2VideoPrefs.quality = VideoQuality.VIDEO_1080_H264
    }

    private fun changeOverlayOption() {
        GeneralPrefs.slotBottomRight1 = OverlayType.EMPTY
    }

    private fun setupStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectCustomSlowCalls()
                // .penaltyFlashScreen()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                // .penaltyDeath()
                .build(),
        )
    }

    companion object {
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val SOURCE_MODE_COMBINED = "combined"
        private const val KEY_PROJECTIVY_SHARED_PROVIDERS = "projectivy_shared_providers"
        private const val PROJECTIVY_PROVIDER_APPLE = "APPLE"
        private const val PROJECTIVY_PROVIDER_AMAZON = "AMAZON"
        private const val PROJECTIVY_PROVIDER_COMM1 = "COMM1"
        private const val PROJECTIVY_PROVIDER_COMM2 = "COMM2"
        private const val PROJECTIVY_PROVIDER_YOUTUBE = "youtube"
        private val LEGACY_PROJECTIVY_DEFAULT_PROVIDERS =
            setOf(
                PROJECTIVY_PROVIDER_AMAZON,
                PROJECTIVY_PROVIDER_COMM1,
                PROJECTIVY_PROVIDER_YOUTUBE,
            )
        private val PROJECTIVY_ALL_DEFAULT_PROVIDERS =
            setOf(
                PROJECTIVY_PROVIDER_APPLE,
                PROJECTIVY_PROVIDER_AMAZON,
                PROJECTIVY_PROVIDER_COMM1,
                PROJECTIVY_PROVIDER_COMM2,
                PROJECTIVY_PROVIDER_YOUTUBE,
            )
    }
}
