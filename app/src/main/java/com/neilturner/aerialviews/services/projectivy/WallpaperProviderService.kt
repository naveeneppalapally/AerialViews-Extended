package com.neilturner.aerialviews.services.projectivy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    
    private val wallpaperCacheLock = Any()
    private val wallpaperBuildMutex = Mutex()


    override fun onCreate() {
        super.onCreate()
        if (!YouTubeFeature.isInitialized()) {
            YouTubeFeature.initialize(applicationContext)
        }
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

                    is Event.LauncherIdleModeChanged -> {
                        if (!event.isIdle) {
                            Log.i("WallpaperProviderService", "Projectivy visible — resuming video")
                            // We don't have direct access to the player from the service. 
                            // This means Projectivy itself handles playback. We'll return the cached wallpapers to resume.
                            cachedWallpapers
                        } else {
                            Log.i("WallpaperProviderService", "Projectivy hidden — pausing video")
                            emptyList()
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
                provider.trim().lowercase()
            }.toSet()

        if (selectedProviders.contains(PROJECTIVY_PROVIDER_YOUTUBE)) {
            if (!YouTubeVideoPrefs.enabled) {
                YouTubeVideoPrefs.enabled = true
                Timber.i("Re-enabled YouTube provider for Projectivy wallpaper mode")
            }
            // Ensure YouTubeFeature is ready before using its provider.
            if (!YouTubeFeature.isInitialized()) {
                YouTubeFeature.initialize(applicationContext)
            }
        }

        val selected = ProjectivyPrefs.sharedProviders
        Log.i("WallpaperProviderService", "Projectivy selected sources: $selected")
        Log.i("WallpaperProviderService", "YouTube enabled: ${YouTubeVideoPrefs.enabled}")
        val cacheSize = kotlinx.coroutines.runBlocking { YouTubeFeature.repository(applicationContext).getCacheSize() }
        Log.i("WallpaperProviderService", "YouTube cache size: $cacheSize")

        val providers = mutableListOf<MediaProvider>().apply {
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_APPLE) || ProjectivyApplePrefs.enabled) {
                add(appleProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_AMAZON) || ProjectivyAmazonPrefs.enabled) {
                add(amazonProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_COMM1) || ProjectivyComm1Prefs.enabled) {
                add(comm1Provider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_COMM2) || ProjectivyComm2Prefs.enabled) {
                add(comm2Provider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_LOCAL)) {
                add(localProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_YOUTUBE)) {
                add(youtubeProvider)
            }
        }

        // If filtering results in an empty list (no providers selected or enabled), 
        // fall back to a safe default of all enabled providers.
        return if (providers.isEmpty()) {
            Timber.w("No providers selected for Projectivy, falling back to all enabled")
            listOf(appleProvider, amazonProvider, comm1Provider, comm2Provider, localProvider, youtubeProvider)
                .filter { it.enabled }
        } else {
            providers
        }
    }

    private companion object {
        const val PROJECTIVY_PROVIDER_APPLE = "apple"
        const val PROJECTIVY_PROVIDER_AMAZON = "amazon"
        const val PROJECTIVY_PROVIDER_COMM1 = "comm1"
        const val PROJECTIVY_PROVIDER_COMM2 = "comm2"
        const val PROJECTIVY_PROVIDER_LOCAL = "local"
        const val PROJECTIVY_PROVIDER_YOUTUBE = "youtube"
        const val WALLPAPER_REUSE_WINDOW_MS = 30_000L
        const val PROVIDER_FETCH_TIMEOUT_MS = 8_000L
        const val YOUTUBE_PROJECTIVY_START_SECONDS = 20
    }

    private fun buildWallpapers(): List<Wallpaper> {
        synchronized(wallpaperCacheLock) {
            val now = System.currentTimeMillis()
            if (cachedWallpapers.isNotEmpty() && now - cachedWallpapersAt < WALLPAPER_REUSE_WINDOW_MS) {
                return cachedWallpapers
            }
        }

        return runBlocking {
            wallpaperBuildMutex.withLock {
                // Double check cache inside mutex
                synchronized(wallpaperCacheLock) {
                    val now = System.currentTimeMillis()
                    if (cachedWallpapers.isNotEmpty() && now - cachedWallpapersAt < WALLPAPER_REUSE_WINDOW_MS) {
                        return@runBlocking cachedWallpapers
                    }
                }

                val enabledProviders = getEnabledProviders()
                Timber.i("Enabled providers for Projectivy: %s", enabledProviders.size)

                val aerialMediaList = supervisorScope {
                    enabledProviders
                        .filter { it.enabled }
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val startedAt = System.currentTimeMillis()
                                val media = runCatching {
                                    withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) {
                                        provider.fetchMedia()
                                    } ?: emptyList()
                                }.onFailure { exception ->
                                    Timber.w(exception, "Projectivy provider failed: %s", provider.javaClass.simpleName)
                                }.getOrDefault(emptyList())

                                if (media.isNotEmpty()) {
                                    Timber.i("Projectivy provider %s returned %s items (elapsed=%sms)",
                                        provider.javaClass.simpleName, media.size, System.currentTimeMillis() - startedAt)
                                }
                                media
                            }
                        }.awaitAll().flatten()
                }

                val wallpapers = aerialMediaList.map { media ->
                    val wallpaperType = when (media.type) {
                        AerialMediaType.VIDEO -> WallpaperType.VIDEO
                        AerialMediaType.IMAGE -> WallpaperType.IMAGE
                    }
                    Wallpaper(
                        wallpaperUri(media),
                        wallpaperType,
                        WallpaperDisplayMode.CROP,
                        title = media.metadata.shortDescription,
                    )
                }.sortedByDescending { it.uri.contains("googlevideo.com") } // Prioritize fresh direct links
                .take(25) // Limit to 25 to avoid overwhelming the launcher
                .let { list ->
                    if (ProjectivyPrefs.shuffleVideos) list.shuffled() else list
                }

                synchronized(wallpaperCacheLock) {
                    cachedWallpapers = wallpapers
                    cachedWallpapersAt = System.currentTimeMillis()
                }
                wallpapers
            }
        }
    }

    private fun wallpaperUri(media: AerialMedia): String {
        val rawUri = media.uri.toString()
        if (media.type != AerialMediaType.VIDEO) {
            return rawUri
        }

        if (YOUTUBE_PROJECTIVY_START_SECONDS <= 0) {
            return rawUri
        }

        // Universal offset for all Projectivy videos (YouTube or others)
        // We append this even to direct stream URLs (googlevideo) as it's the only way 
        // to tell the launcher's player where to start.
        return if (rawUri.contains("#")) {
            "$rawUri&t=$YOUTUBE_PROJECTIVY_START_SECONDS"
        } else {
            "$rawUri#t=$YOUTUBE_PROJECTIVY_START_SECONDS"
        }
    }
}
