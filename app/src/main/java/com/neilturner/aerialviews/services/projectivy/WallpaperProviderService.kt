package com.neilturner.aerialviews.services.projectivy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.videos.AerialMedia
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
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeMediaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {
    private val appleProvider by lazy { AppleMediaProvider(applicationContext, ProjectivyApplePrefs) }
    private val amazonProvider by lazy { AmazonMediaProvider(applicationContext, ProjectivyAmazonPrefs) }
    private val comm1Provider by lazy { Comm1MediaProvider(applicationContext, ProjectivyComm1Prefs) }
    private val comm2Provider by lazy { Comm2MediaProvider(applicationContext, ProjectivyComm2Prefs) }
    private val localProvider by lazy { LocalMediaProvider(applicationContext, ProjectivyLocalMediaPrefs) }
    private val youtubeProvider by lazy { YouTubeMediaProvider(applicationContext) }

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
        val selectedProviders =
            ProjectivyPrefs.sharedProviders.map { provider ->
                provider.trim()
            }.toSet()

        ProjectivyApplePrefs.enabled = selectedProviders.contains(PROJECTIVY_PROVIDER_APPLE)
        ProjectivyAmazonPrefs.enabled = selectedProviders.contains(PROJECTIVY_PROVIDER_AMAZON)
        ProjectivyComm1Prefs.enabled = selectedProviders.contains(PROJECTIVY_PROVIDER_COMM1)
        ProjectivyComm2Prefs.enabled = selectedProviders.contains(PROJECTIVY_PROVIDER_COMM2)

        if (selectedProviders.contains(PROJECTIVY_PROVIDER_YOUTUBE) && !YouTubeVideoPrefs.enabled) {
            YouTubeVideoPrefs.enabled = true
            Timber.i("Re-enabled YouTube provider for Projectivy wallpaper mode")
        }

        return mutableListOf<MediaProvider>().apply {
            if (ProjectivyApplePrefs.enabled) {
                add(appleProvider)
            }
            if (ProjectivyAmazonPrefs.enabled) {
                add(amazonProvider)
            }
            if (ProjectivyComm1Prefs.enabled) {
                add(comm1Provider)
            }
            if (ProjectivyComm2Prefs.enabled) {
                add(comm2Provider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_LOCAL)) {
                add(localProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_YOUTUBE)) {
                add(youtubeProvider)
            }
        }
    }

    private companion object {
        const val PROJECTIVY_PROVIDER_APPLE = "APPLE"
        const val PROJECTIVY_PROVIDER_AMAZON = "AMAZON"
        const val PROJECTIVY_PROVIDER_COMM1 = "COMM1"
        const val PROJECTIVY_PROVIDER_COMM2 = "COMM2"
        const val PROJECTIVY_PROVIDER_LOCAL = "LOCAL"
        const val PROJECTIVY_PROVIDER_YOUTUBE = "youtube"
        const val WALLPAPER_REUSE_WINDOW_MS = 30_000L
        const val PROVIDER_FETCH_TIMEOUT_MS = 8_000L
        const val YOUTUBE_PROJECTIVY_START_SECONDS = 20
    }

    private fun buildWallpapers(): List<Wallpaper> {
        val enabledProviders = getEnabledProviders()
        Timber.i("Enabled providers: %s", enabledProviders.size)

        val aerialMediaList =
            runBlocking {
                supervisorScope {
                    enabledProviders
                        .filter { it.enabled }
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val startedAt = System.currentTimeMillis()
                                val media =
                                    runCatching {
                                        withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) {
                                            provider.fetchMedia()
                                        } ?: emptyList()
                                    }.onFailure { exception ->
                                        Timber.w(exception, "Projectivy provider failed: %s", provider.javaClass.simpleName)
                                    }.getOrDefault(emptyList())

                                if (media.isEmpty()) {
                                    Timber.i(
                                        "Projectivy provider %s returned no media (elapsed=%sms)",
                                        provider.javaClass.simpleName,
                                        System.currentTimeMillis() - startedAt,
                                    )
                                } else {
                                    Timber.i(
                                        "Projectivy provider %s returned %s items (elapsed=%sms)",
                                        provider.javaClass.simpleName,
                                        media.size,
                                        System.currentTimeMillis() - startedAt,
                                    )
                                }
                                media
                            }
                        }.awaitAll()
                        .flatten()
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
                wallpaperUri(media),
                wallpaperType,
                WallpaperDisplayMode.CROP,
                title = media.metadata.shortDescription,
            )
        }
    }

    private fun wallpaperUri(media: AerialMedia): String {
        val rawUri = media.uri.toString()
        if (media.type != AerialMediaType.VIDEO || media.source != AerialMediaSource.YOUTUBE) {
            return rawUri
        }
        return if (rawUri.contains("#")) {
            "$rawUri&t=$YOUTUBE_PROJECTIVY_START_SECONDS"
        } else {
            "$rawUri#t=$YOUTUBE_PROJECTIVY_START_SECONDS"
        }
    }
}
