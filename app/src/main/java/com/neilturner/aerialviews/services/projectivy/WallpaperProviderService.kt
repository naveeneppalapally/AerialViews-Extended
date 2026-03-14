package com.neilturner.aerialviews.services.projectivy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.prefs.ProjectivyAmazonPrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyApplePrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyComm1Prefs
import com.neilturner.aerialviews.models.prefs.ProjectivyComm2Prefs
import com.neilturner.aerialviews.models.prefs.ProjectivyLocalMediaPrefs
import com.neilturner.aerialviews.models.prefs.ProjectivyPrefs
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.AmazonMediaProvider
import com.neilturner.aerialviews.providers.AppleMediaProvider
import com.neilturner.aerialviews.providers.Comm1MediaProvider
import com.neilturner.aerialviews.providers.Comm2MediaProvider
import com.neilturner.aerialviews.providers.LocalMediaProvider
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.youtube.YouTubeMediaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedWallpapers: List<Wallpaper> = emptyList()

    @Volatile
    private var lastWallpaperBuildAt: Long = 0L

    @Volatile
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scheduleWallpaperRefresh(force = true)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder =
        object : IWallpaperProviderService.Stub() {
            override fun getWallpapers(event: Event?): List<Wallpaper> {
                when (event) {
                    is Event.TimeElapsed -> {
                        if (shouldRefreshWallpapers()) {
                            scheduleWallpaperRefresh(force = true)
                        }
                    }
                    is Event.LauncherIdleModeChanged -> {
                        if (event.isIdle && cachedWallpapers.isEmpty()) {
                            scheduleWallpaperRefresh(force = true)
                        }
                    }
                    else -> {
                        if (cachedWallpapers.isEmpty()) {
                            scheduleWallpaperRefresh(force = false)
                        }
                    }
                }
                return cachedWallpapers
            }

            override fun getPreferences(): String? = null

            override fun setPreferences(params: String?) {
            }
        }

    private fun getEnabledProviders(): List<MediaProvider> =
        mutableListOf<MediaProvider>().apply {
            add(AppleMediaProvider(applicationContext, ProjectivyApplePrefs))
            add(Comm1MediaProvider(applicationContext, ProjectivyComm1Prefs))
            add(Comm2MediaProvider(applicationContext, ProjectivyComm2Prefs))
            add(AmazonMediaProvider(applicationContext, ProjectivyAmazonPrefs))
            add(LocalMediaProvider(applicationContext, ProjectivyLocalMediaPrefs))
            if (YouTubeVideoPrefs.enabled && ProjectivyPrefs.sharedProviders.contains(PROJECTIVY_YOUTUBE_PROVIDER)) {
                add(YouTubeMediaProvider(applicationContext))
            }
        }

    private fun shouldRefreshWallpapers(now: Long = System.currentTimeMillis()): Boolean =
        cachedWallpapers.isEmpty() || now - lastWallpaperBuildAt >= WALLPAPER_REUSE_WINDOW_MS

    private fun scheduleWallpaperRefresh(force: Boolean) {
        val activeJob = refreshJob
        if (activeJob?.isActive == true) {
            return
        }
        if (!force && !shouldRefreshWallpapers()) {
            return
        }

        refreshJob =
            serviceScope.launch {
                val freshWallpapers = buildWallpapers()
                if (freshWallpapers.isNotEmpty()) {
                    cachedWallpapers = freshWallpapers
                    lastWallpaperBuildAt = System.currentTimeMillis()
                    Timber.i("Updated cached Projectivy wallpapers: %s", freshWallpapers.size)
                } else {
                    Timber.w("Projectivy wallpaper refresh produced no items; keeping existing cache")
                }
            }
    }

    private companion object {
        const val PROJECTIVY_YOUTUBE_PROVIDER = "youtube"
        const val WALLPAPER_REUSE_WINDOW_MS = 90_000L
    }

    private suspend fun buildWallpapers(): List<Wallpaper> {
        val enabledProviders = getEnabledProviders()
        Timber.i("Enabled providers: %s", enabledProviders.size)

        val aerialMediaList =
            enabledProviders
                .filter { it.enabled }
                .flatMap { provider ->
                    try {
                        provider.fetchMedia()
                    } catch (exception: Exception) {
                        Timber.w(exception, "Projectivy provider failed: %s", provider.javaClass.simpleName)
                        emptyList()
                    }
                }.let { mediaList ->
                    Timber.log(2, "Wallpaper media items: %s", mediaList.size)
                    if (ProjectivyPrefs.shuffleVideos) {
                        mediaList.shuffled()
                    } else {
                        mediaList
                    }
                }

        return aerialMediaList.map { media ->
            val wallpaperType =
                when (media.type) {
                    AerialMediaType.VIDEO -> WallpaperType.VIDEO
                    AerialMediaType.IMAGE -> WallpaperType.IMAGE
                }
            Wallpaper(
                media.uri.toString(),
                wallpaperType,
                WallpaperDisplayMode.DEFAULT,
                title = media.metadata.shortDescription,
            )
        }
    }
}
