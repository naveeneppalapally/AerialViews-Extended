package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.hardware.display.DisplayManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object YouTubeFeature {
    const val DAILY_REFRESH_WORK_NAME = "youtube_daily_refresh"
    const val STREAM_REFRESH_WORK_NAME = "youtube_stream_refresh"
    const val STARTUP_REFRESH_WORK_NAME = "youtube_startup_refresh"
    const val ON_DEMAND_REFRESH_WORK_NAME = "youtube_on_demand_refresh"

    @Volatile
    private var repository: YouTubeSourceRepository? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        NewPipeHelper.init()
        initializePreferences(appContext)
        scheduleAutomaticRefresh(appContext)
    }

    fun repository(context: Context): YouTubeSourceRepository {
        val appContext = context.applicationContext
        return repository
            ?: synchronized(this) {
                repository
                    ?: YouTubeSourceRepository(
                        cacheDao = YouTubeCacheDatabase.getInstance(appContext).youtubeCacheDao(),
                        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext),
                    ).also { repository = it }
            }
    }

    fun requestImmediateRefresh(
        context: Context,
        forceSearchRefresh: Boolean = true,
    ) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val request =
            OneTimeWorkRequestBuilder<YouTubeRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to forceSearchRefresh),
                )
                .addTag(ON_DEMAND_REFRESH_WORK_NAME)
                .build()

        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniqueWork(
                ON_DEMAND_REFRESH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    private fun scheduleAutomaticRefresh(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val dailyRequest =
            PeriodicWorkRequestBuilder<YouTubeRefreshWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisecondsUntilNextThreeAm(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to true),
                )
                .addTag(DAILY_REFRESH_WORK_NAME)
                .build()

        val streamRequest =
            PeriodicWorkRequestBuilder<YouTubeRefreshWorker>(STREAM_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setInitialDelay(STREAM_REFRESH_INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to false),
                )
                .addTag(STREAM_REFRESH_WORK_NAME)
                .build()

        val startupRequest =
            OneTimeWorkRequestBuilder<YouTubeRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to false),
                )
                .addTag(STARTUP_REFRESH_WORK_NAME)
                .build()

        workManager.enqueueUniquePeriodicWork(
            DAILY_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dailyRequest,
        )
        workManager.enqueueUniquePeriodicWork(
            STREAM_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            streamRequest,
        )
        workManager.enqueueUniqueWork(
            STARTUP_REFRESH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            startupRequest,
        )
    }

    private fun initializePreferences(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hasInitializedQuality = prefs.getBoolean(KEY_QUALITY_INITIALIZED, false)
        if (!hasInitializedQuality) {
            val configuredQuality = prefs.getString(YouTubeSourceRepository.KEY_QUALITY, null)?.trim()
            val deviceDefaultQuality = if (supportsUltraHdOutput(context)) UHD_QUALITY else YouTubeSourceRepository.DEFAULT_QUALITY
            val resolvedQuality =
                when {
                    configuredQuality.isNullOrBlank() -> deviceDefaultQuality
                    configuredQuality.equals("4k", ignoreCase = true) -> UHD_QUALITY
                    configuredQuality == YouTubeSourceRepository.DEFAULT_QUALITY && deviceDefaultQuality == UHD_QUALITY -> UHD_QUALITY
                    else -> configuredQuality
                }

            prefs.edit {
                putString(YouTubeSourceRepository.KEY_QUALITY, resolvedQuality)
                putBoolean(KEY_QUALITY_INITIALIZED, true)
            }
        }

        if (!prefs.contains("yt_enabled")) {
            prefs.edit {
                putBoolean("yt_enabled", true)
            }
        }

        if (!prefs.contains(YouTubeSourceRepository.KEY_MIX_WEIGHT)) {
            prefs.edit {
                putString(YouTubeSourceRepository.KEY_MIX_WEIGHT, YouTubeSourceRepository.DEFAULT_MIX_WEIGHT)
            }
        }
    }

    private fun supportsUltraHdOutput(context: Context): Boolean {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        return displayManager.displays.any { display ->
            val mode = display.mode
            val activeModeSupportsUhd =
                mode != null && (mode.physicalWidth >= UHD_WIDTH || mode.physicalHeight >= UHD_HEIGHT)
            val supportedModeHasUhd =
                display.supportedModes.any { modeInfo ->
                    modeInfo.physicalWidth >= UHD_WIDTH || modeInfo.physicalHeight >= UHD_HEIGHT
                }
            activeModeSupportsUhd || supportedModeHasUhd
        }
    }

    private fun millisecondsUntilNextThreeAm(): Long {
        val now = ZonedDateTime.now()
        var nextRun = now.with(LocalTime.of(3, 0)).withSecond(0).withNano(0)
        if (!nextRun.isAfter(now)) {
            nextRun = nextRun.plusDays(1)
        }
        return Duration.between(now, nextRun).toMillis().coerceAtLeast(0L)
    }

    private const val STREAM_REFRESH_INTERVAL_MINUTES = 330L
    private const val STREAM_REFRESH_INITIAL_DELAY_MINUTES = 15L
    private const val KEY_QUALITY_INITIALIZED = "yt_quality_initialized"
    private const val UHD_QUALITY = "2160p"
    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160
}
