package com.neilturner.aerialviews.services.projectivy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.prefs.ProjectivyPrefs
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeMediaProvider
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {
    @Volatile
    private var cachedWallpapers: List<Wallpaper> = emptyList()

    @Volatile
    private var cachedWallpapersAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        YouTubeFeature.initialize(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder =
        object : IWallpaperProviderService.Stub() {
            override fun getWallpapers(event: Event?): List<Wallpaper> =
                when (event) {
                    is Event.TimeElapsed -> {
                        cachedWallpapers
                            .takeIf { it.isNotEmpty() && System.currentTimeMillis() - cachedWallpapersAt < WALLPAPER_REUSE_WINDOW_MS }
                            ?.also {
                                Timber.i("Reusing cached Projectivy wallpapers: %s", it.size)
                            }
                            ?: buildWallpapers().also { wallpapers ->
                                cachedWallpapers = wallpapers
                                cachedWallpapersAt = System.currentTimeMillis()
                            }
                    }

                    else -> {
                        emptyList()
                    } // Returning an empty list won't change the currently displayed wallpaper
                }

            override fun getPreferences(): String? = null

            override fun setPreferences(params: String?) {
            }
        }

    private fun getEnabledProviders(): List<MediaProvider> {
        val normalizedProviders =
            ProjectivyPrefs.sharedProviders.map { provider ->
                provider.trim().lowercase()
            }.toSet()
        if (!normalizedProviders.contains(PROJECTIVY_YOUTUBE_PROVIDER)) {
            return emptyList()
        }
        if (!YouTubeVideoPrefs.enabled) {
            YouTubeVideoPrefs.enabled = true
            Timber.i("Re-enabled YouTube provider for Projectivy wallpaper mode")
        }
        return listOf(YouTubeMediaProvider(applicationContext))
    }

    private companion object {
        const val PROJECTIVY_YOUTUBE_PROVIDER = "youtube"
        const val WALLPAPER_REUSE_WINDOW_MS = 30_000L
    }

    private fun buildWallpapers(): List<Wallpaper> {
        val enabledProviders = getEnabledProviders()
        Timber.i("Enabled providers: %s", enabledProviders.size)

        val aerialMediaList =
            runBlocking {
                enabledProviders
                    .filter { it.enabled }
                    .flatMap { provider ->
                        try {
                            provider.fetchMedia()
                        } catch (exception: Exception) {
                            Timber.w(exception, "Projectivy provider failed: %s", provider.javaClass.simpleName)
                            emptyList()
                        }
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
                WallpaperDisplayMode.CROP,
                title = media.metadata.shortDescription,
            )
        }
    }
}
