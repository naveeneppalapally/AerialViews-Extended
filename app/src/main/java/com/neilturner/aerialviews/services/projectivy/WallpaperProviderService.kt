package com.neilturner.aerialviews.services.projectivy

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
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

    @Volatile
    private var cachedWallpaperConfigSignature: String = ""
    
    private val wallpaperCacheLock = Any()
    private val wallpaperBuildMutex = Mutex()
    private val serveHistoryLock = Any()
    private val servedWallpaperKeys = ArrayDeque<String>()
    private var serveRotationCursor: Int = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecordedYouTubeKey: String = ""
    private var lastRecordedYouTubePlaybackAtMs: Long = 0L


    override fun onCreate() {
        super.onCreate()
        if (!YouTubeFeature.isInitialized()) {
            YouTubeFeature.initialize(applicationContext)
        }
        loadServedWallpaperHistory()
        cachedWallpaperConfigSignature = currentWallpaperConfigSignature()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder =
        object : IWallpaperProviderService.Stub() {
            override fun getWallpapers(event: Event?): List<Wallpaper> =
                run {
                    Log.i(
                        "WallpaperProviderService",
                        "getWallpapers event=${event?.javaClass?.simpleName ?: "null"} idleEvent=${(event as? Event.LauncherIdleModeChanged)?.isIdle}",
                    )
                    when (event) {
                        is Event.TimeElapsed -> {
                            val wallpapers = loadOrBuildWallpapers(forceRefresh = false)
                            val shuffled =
                                if (ProjectivyPrefs.shuffleVideos && wallpapers.size > 1) {
                                    wallpapers.shuffled()
                                } else {
                                    wallpapers
                                }
                            prepareWallpapersForResponse(shuffled).also { prepared ->
                                Log.i("WallpaperProviderService", "returning wallpapers=${prepared.size} for TimeElapsed")
                            }
                        }

                        is Event.LauncherIdleModeChanged -> {
                            if (!event.isIdle) {
                                Log.i("WallpaperProviderService", "Projectivy visible — resuming video")
                                // Keep current playlist continuity; config-change invalidation will rebuild when needed.
                                val wallpapers = loadOrBuildWallpapers(forceRefresh = false)
                                val shuffled =
                                    if (ProjectivyPrefs.shuffleVideos && wallpapers.size > 1) {
                                        wallpapers.shuffled()
                                    } else {
                                        wallpapers
                                    }
                                prepareWallpapersForResponse(shuffled).also { prepared ->
                                    Log.i("WallpaperProviderService", "returning wallpapers=${prepared.size} for LauncherIdleModeChanged(visible)")
                                }
                            } else {
                                Log.i("WallpaperProviderService", "Projectivy hidden — pausing video")
                                emptyList()
                            }
                        }

                        else -> {
                            emptyList()
                        } // Returning an empty list won't change the currently displayed wallpaper
                    }
                }

            override fun getPreferences(): String? = null

            override fun setPreferences(params: String?) {
            }
        }

    private fun loadOrBuildWallpapers(forceRefresh: Boolean): List<Wallpaper> {
        invalidateWallpaperCacheIfConfigChanged()
        if (!forceRefresh) {
            synchronized(wallpaperCacheLock) {
                val now = System.currentTimeMillis()
                if (cachedWallpapers.isNotEmpty() && now - cachedWallpapersAt < WALLPAPER_REUSE_WINDOW_MS) {
                    Timber.i("Reusing cached Projectivy wallpapers: %s", cachedWallpapers.size)
                    return cachedWallpapers
                }
            }
        }
        return buildWallpapers()
    }

    private fun prepareWallpapersForResponse(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) {
            return wallpapers
        }
        val ordered = reorderWallpapersForNovelty(wallpapers)
        ordered.firstOrNull()?.uri?.let(::recordServedWallpaper)
        return ordered
    }

    private fun reorderWallpapersForNovelty(wallpapers: List<Wallpaper>): List<Wallpaper> {
        val unseen = mutableListOf<Wallpaper>()
        val seen = mutableListOf<Wallpaper>()
        val (recentKeySet, recencyOrderByKey) =
            synchronized(serveHistoryLock) {
                servedWallpaperKeys.toSet() to servedWallpaperKeys.withIndex().associate { indexed -> indexed.value to indexed.index }
            }
        wallpapers.forEach { wallpaper ->
            val key = wallpaperHistoryKey(wallpaper.uri)
            if (key in recentKeySet) {
                seen += wallpaper
            } else {
                unseen += wallpaper
            }
        }

        val reorderedBase =
            if (recentKeySet.isNotEmpty() && unseen.isNotEmpty()) {
                unseen + seen.sortedBy { wallpaper ->
                    recencyOrderByKey[wallpaperHistoryKey(wallpaper.uri)] ?: Int.MAX_VALUE
                }
            } else if (recentKeySet.isNotEmpty()) {
                wallpapers.sortedBy { wallpaper ->
                    recencyOrderByKey[wallpaperHistoryKey(wallpaper.uri)] ?: Int.MAX_VALUE
                }
            } else {
                wallpapers
            }

        if (reorderedBase.size <= 1) {
            return reorderedBase
        }

        val rotationIndex =
            synchronized(serveHistoryLock) {
                val index = ((serveRotationCursor % reorderedBase.size) + reorderedBase.size) % reorderedBase.size
                serveRotationCursor = (index + 1) % reorderedBase.size
                persistServeRotationCursorLocked()
                index
            }
        return reorderedBase.drop(rotationIndex) + reorderedBase.take(rotationIndex)
    }

    private fun recordServedWallpaper(uri: String) {
        val key = wallpaperHistoryKey(uri)
        if (key.isBlank()) {
            return
        }
        synchronized(serveHistoryLock) {
            servedWallpaperKeys.remove(key)
            servedWallpaperKeys.addLast(key)
            while (servedWallpaperKeys.size > MAX_SERVED_WALLPAPER_HISTORY) {
                servedWallpaperKeys.removeFirst()
            }
            persistServedWallpaperHistory()
        }
        maybeRecordYouTubePlayback(key)
    }

    private fun maybeRecordYouTubePlayback(key: String) {
        if (!key.startsWith("yt:")) {
            return
        }
        val videoId = key.removePrefix("yt:").trim()
        if (videoId.isBlank()) {
            return
        }

        val shouldRecord =
            synchronized(serveHistoryLock) {
                val now = System.currentTimeMillis()
                val duplicateKey = key == lastRecordedYouTubeKey
                val withinCooldown = now - lastRecordedYouTubePlaybackAtMs < PLAYBACK_RECORD_COOLDOWN_MS
                if (duplicateKey || withinCooldown) {
                    false
                } else {
                    lastRecordedYouTubeKey = key
                    lastRecordedYouTubePlaybackAtMs = now
                    true
                }
            }
        if (!shouldRecord) {
            return
        }

        serviceScope.launch {
            runCatching {
                YouTubeFeature.repository(applicationContext).markAsPlayed(videoId)
            }.onFailure { exception ->
                Timber.w(exception, "Failed to record Projectivy playback for YouTube videoId=%s", videoId)
            }
        }
    }

    private fun loadServedWallpaperHistory() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val raw =
            prefs.getString(KEY_PROJECTIVY_SERVED_WALLPAPER_HISTORY, "").orEmpty()
        val loaded =
            raw.split(SERVED_WALLPAPER_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotBlank)
                .takeLast(MAX_SERVED_WALLPAPER_HISTORY)
        val loadedCursor = prefs.getInt(KEY_PROJECTIVY_SERVED_ROTATION_CURSOR, 0).coerceAtLeast(0)
        synchronized(serveHistoryLock) {
            servedWallpaperKeys.clear()
            loaded.forEach(servedWallpaperKeys::addLast)
            lastRecordedYouTubeKey = loaded.lastOrNull { it.startsWith("yt:") }.orEmpty()
            serveRotationCursor = loadedCursor
        }
    }

    private fun persistServedWallpaperHistory() {
        PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
            .edit()
            .putString(
                KEY_PROJECTIVY_SERVED_WALLPAPER_HISTORY,
                servedWallpaperKeys.joinToString(SERVED_WALLPAPER_SEPARATOR),
            ).putInt(
                KEY_PROJECTIVY_SERVED_ROTATION_CURSOR,
                serveRotationCursor,
            ).apply()
    }

    private fun persistServeRotationCursorLocked() {
        PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
            .edit()
            .putInt(KEY_PROJECTIVY_SERVED_ROTATION_CURSOR, serveRotationCursor)
            .apply()
    }

    private fun wallpaperHistoryKey(uri: String): String {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        val host = parsed?.host.orEmpty().lowercase()
        if (host.contains("youtube.com")) {
            parsed?.getQueryParameter("v")
                ?.takeIf(String::isNotBlank)
                ?.let { return "yt:$it" }
        }
        if (host.contains("youtu.be")) {
            parsed?.lastPathSegment
                ?.takeIf(String::isNotBlank)
                ?.let { return "yt:$it" }
        }
        if (host.contains("googlevideo.com")) {
            parsed?.getQueryParameter("id")
                ?.substringBefore('.')
                ?.takeIf(String::isNotBlank)
                ?.let { return "yt:$it" }
        }
        return uri.substringBefore('#').substringBefore('?').ifBlank { uri }
    }

    private fun getEnabledProviders(): List<MediaProvider> {
        val selectedProviders = selectedProviderKeys()

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

        Log.i("WallpaperProviderService", "Projectivy selected sources: $selectedProviders")
        Log.i("WallpaperProviderService", "YouTube enabled: ${YouTubeVideoPrefs.enabled}")
        val cacheSize = kotlinx.coroutines.runBlocking { YouTubeFeature.repository(applicationContext).getCacheSize() }
        Log.i("WallpaperProviderService", "YouTube cache size: $cacheSize")

        val providers = mutableListOf<MediaProvider>().apply {
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_APPLE)) {
                add(appleProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_AMAZON)) {
                add(amazonProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_COMM1)) {
                add(comm1Provider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_COMM2)) {
                add(comm2Provider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_LOCAL)) {
                add(localProvider)
            }
            if (selectedProviders.contains(PROJECTIVY_PROVIDER_YOUTUBE)) {
                add(youtubeProvider)
            }
        }

        // If no providers are selected, fall back to YouTube so Projectivy always has a candidate.
        return if (providers.isEmpty()) {
            Timber.w("No providers selected for Projectivy, falling back to YouTube provider")
            listOf(youtubeProvider)
        } else {
            providers
        }
    }

    private fun selectedProviderKeys(): Set<String> {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val defaults = resources.getStringArray(R.array.projectivy_shared_providers_default).toSet()
        return sharedPrefs
            .getStringSet("projectivy_shared_providers", defaults)
            .orEmpty()
            .map { provider -> provider.trim().lowercase() }
            .filter { provider -> provider.isNotBlank() }
            .toSet()
    }

    private fun currentWallpaperConfigSignature(): String =
        buildString {
            append(selectedProviderKeys().toList().sorted().joinToString(","))
            append("|yt=")
            append(YouTubeVideoPrefs.enabled)
            append("|ytQuality=")
            append(YouTubeVideoPrefs.quality)
            append("|ytShuffle=")
            append(YouTubeVideoPrefs.shuffle)
            append("|ytMix=")
            append(YouTubeVideoPrefs.mixWeight)
            append("|ytPlaybackMode=")
            append(YouTubeVideoPrefs.playbackLengthMode)
            append("|ytPlaybackMaxMinutes=")
            append(YouTubeVideoPrefs.playbackMaxMinutes)
            append("|ytCategories=")
            append(
                listOf(
                    YouTubeVideoPrefs.categoryNature,
                    YouTubeVideoPrefs.categoryAnimals,
                    YouTubeVideoPrefs.categoryDrone,
                    YouTubeVideoPrefs.categoryCities,
                    YouTubeVideoPrefs.categorySpace,
                    YouTubeVideoPrefs.categoryOcean,
                    YouTubeVideoPrefs.categoryWeather,
                    YouTubeVideoPrefs.categoryWinter,
                ).joinToString(","),
            )
            append("|shuffle=")
            append(ProjectivyPrefs.shuffleVideos)
            append("|appleQuality=")
            append(ProjectivyApplePrefs.quality)
            append("|appleScene=")
            append(ProjectivyApplePrefs.scene.sorted().joinToString(","))
            append("|appleTime=")
            append(ProjectivyApplePrefs.timeOfDay.sorted().joinToString(","))
            append("|amazonQuality=")
            append(ProjectivyAmazonPrefs.quality)
            append("|amazonScene=")
            append(ProjectivyAmazonPrefs.scene.sorted().joinToString(","))
            append("|amazonTime=")
            append(ProjectivyAmazonPrefs.timeOfDay.sorted().joinToString(","))
            append("|comm1Quality=")
            append(ProjectivyComm1Prefs.quality)
            append("|comm1Scene=")
            append(ProjectivyComm1Prefs.scene.sorted().joinToString(","))
            append("|comm1Time=")
            append(ProjectivyComm1Prefs.timeOfDay.sorted().joinToString(","))
            append("|comm2Quality=")
            append(ProjectivyComm2Prefs.quality)
            append("|comm2Scene=")
            append(ProjectivyComm2Prefs.scene.sorted().joinToString(","))
            append("|comm2Time=")
            append(ProjectivyComm2Prefs.timeOfDay.sorted().joinToString(","))
            append("|localSearchType=")
            append(ProjectivyLocalMediaPrefs.searchType)
            append("|localMediaType=")
            append(ProjectivyLocalMediaPrefs.mediaType)
            append("|localFilterEnabled=")
            append(ProjectivyLocalMediaPrefs.filterEnabled)
            append("|localFilterFolder=")
            append(ProjectivyLocalMediaPrefs.filterFolder)
        }

    private fun invalidateWallpaperCacheIfConfigChanged() {
        val currentSignature = currentWallpaperConfigSignature()
        synchronized(wallpaperCacheLock) {
            if (cachedWallpaperConfigSignature == currentSignature) {
                return
            }
            Timber.i(
                "Projectivy config changed. Invalidating wallpaper cache (old=%s, new=%s)",
                cachedWallpaperConfigSignature,
                currentSignature,
            )
            cachedWallpapers = emptyList()
            cachedWallpapersAt = 0L
            cachedWallpaperConfigSignature = currentSignature
        }
    }

    private companion object {
        const val PROJECTIVY_PROVIDER_APPLE = "apple"
        const val PROJECTIVY_PROVIDER_AMAZON = "amazon"
        const val PROJECTIVY_PROVIDER_COMM1 = "comm1"
        const val PROJECTIVY_PROVIDER_COMM2 = "comm2"
        const val PROJECTIVY_PROVIDER_LOCAL = "local"
        const val PROJECTIVY_PROVIDER_YOUTUBE = "youtube"
        const val WALLPAPER_REUSE_WINDOW_MS = 5_000L
        const val PROVIDER_FETCH_TIMEOUT_MS = 12_000L
        const val YOUTUBE_PROJECTIVY_START_SECONDS = 30
        const val KEY_PROJECTIVY_SERVED_WALLPAPER_HISTORY = "projectivy_served_wallpaper_history"
        const val KEY_PROJECTIVY_SERVED_ROTATION_CURSOR = "projectivy_served_rotation_cursor"
        const val MAX_SERVED_WALLPAPER_HISTORY = 300
        const val SERVED_WALLPAPER_SEPARATOR = "|"
        const val PLAYBACK_RECORD_COOLDOWN_MS = 15_000L
        const val PROJECTIVY_WALLPAPER_LIMIT = 40
        const val PROJECTIVY_YOUTUBE_DIRECT_RESOLVE_LIMIT = 20
        const val MIN_PROJECTIVY_PLAYABLE_ITEMS = 8
        const val YOUTUBE_PROJECTIVY_DIRECT_RESOLVE_TIMEOUT_MS = 4_000L
        const val PROJECTIVY_BOOTSTRAP_PROVIDER_KEY = "youtube_bootstrap"
        val PROJECTIVY_BOOTSTRAP_VIDEO_URLS =
            listOf(
                "https://www.youtube.com/watch?v=BHACKCNDMW8",
                "https://www.youtube.com/watch?v=LXb3EKWsInQ",
            )
    }

    private fun buildWallpapers(): List<Wallpaper> {
        invalidateWallpaperCacheIfConfigChanged()
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
                        return@runBlocking if (ProjectivyPrefs.shuffleVideos) cachedWallpapers.shuffled() else cachedWallpapers
                    }
                }

                val enabledProviders = getEnabledProviders()
                Timber.i("Enabled providers for Projectivy: %s", enabledProviders.size)

                var providerMediaBatches = supervisorScope {
                    enabledProviders
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val providerKey = provider.javaClass.simpleName
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
                                providerKey to media
                            }
                        }.awaitAll()
                }
                Log.i(
                    "WallpaperProviderService",
                    "provider batches: ${
                        providerMediaBatches.joinToString { (key, media) -> "$key=${media.size}" }
                    }",
                )

                val youtubeProviderEnabled =
                    enabledProviders.any { provider ->
                        provider is YouTubeMediaProvider
                    }
                if (providerMediaBatches.none { (_, media) -> media.isNotEmpty() } && youtubeProviderEnabled) {
                    Timber.w("Projectivy providers returned no media; using YouTube bootstrap fallback list")
                    providerMediaBatches = listOf(PROJECTIVY_BOOTSTRAP_PROVIDER_KEY to projectivyBootstrapMedia())
                }

                val fairMediaSelection =
                    buildFairMediaSelection(
                        providerMediaBatches = providerMediaBatches,
                        limit = PROJECTIVY_WALLPAPER_LIMIT,
                    )
                val playableMediaSelection = ensureProjectivyPlayableMedia(fairMediaSelection)
                Log.i(
                    "WallpaperProviderService",
                    "selected fair=${fairMediaSelection.size} playable=${playableMediaSelection.size}",
                )

                val wallpapers = playableMediaSelection.map { media ->
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
                }

                if (wallpapers.isEmpty()) {
                    synchronized(wallpaperCacheLock) {
                        if (cachedWallpapers.isNotEmpty()) {
                            Timber.w("Projectivy refresh produced empty wallpapers; reusing previous cache without extending cache TTL")
                            return@withLock cachedWallpapers
                        }
                    }
                }

                synchronized(wallpaperCacheLock) {
                    cachedWallpapers = wallpapers
                    cachedWallpapersAt = if (wallpapers.isNotEmpty()) System.currentTimeMillis() else 0L
                    cachedWallpaperConfigSignature = currentWallpaperConfigSignature()
                }
                wallpapers
            }
        }
    }

    private fun projectivyBootstrapMedia(): List<AerialMedia> =
        PROJECTIVY_BOOTSTRAP_VIDEO_URLS.map { videoPageUrl ->
            AerialMedia(
                uri = Uri.parse(videoPageUrl),
                type = AerialMediaType.VIDEO,
                source = AerialMediaSource.YOUTUBE,
                metadata =
                    AerialMediaMetadata(
                        shortDescription = "Projectivy YouTube Bootstrap",
                        exif =
                            AerialExifMetadata(
                                description = videoPageUrl,
                                durationSeconds = 0,
                            ),
                    ),
            )
        }

    private fun buildFairMediaSelection(
        providerMediaBatches: List<Pair<String, List<AerialMedia>>>,
        limit: Int,
    ): List<AerialMedia> {
        if (limit <= 0 || providerMediaBatches.isEmpty()) {
            return emptyList()
        }

        val buckets = linkedMapOf<String, ArrayDeque<AerialMedia>>()
        providerMediaBatches.forEach { (providerKey, media) ->
            if (media.isEmpty()) {
                return@forEach
            }
            val preparedMedia =
                if (ProjectivyPrefs.shuffleVideos && media.size > 1) {
                    media.shuffled()
                } else {
                    media
                }
            buckets[providerKey] = ArrayDeque(preparedMedia)
        }
        if (buckets.isEmpty()) {
            return emptyList()
        }

        val activeProviders =
            if (ProjectivyPrefs.shuffleVideos && buckets.size > 1) {
                buckets.keys.shuffled().toMutableList()
            } else {
                buckets.keys.toMutableList()
            }

        val selected = mutableListOf<AerialMedia>()
        while (selected.size < limit && activeProviders.isNotEmpty()) {
            val iterator = activeProviders.iterator()
            while (iterator.hasNext() && selected.size < limit) {
                val providerKey = iterator.next()
                val bucket = buckets[providerKey]
                if (bucket == null || bucket.isEmpty()) {
                    iterator.remove()
                    continue
                }
                selected += bucket.removeFirst()
                if (bucket.isEmpty()) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private suspend fun ensureProjectivyPlayableMedia(mediaItems: List<AerialMedia>): List<AerialMedia> {
        if (mediaItems.isEmpty()) {
            return mediaItems
        }

        val candidates =
            mediaItems.withIndex().filter { (_, media) ->
                media.source == AerialMediaSource.YOUTUBE &&
                    media.type == AerialMediaType.VIDEO &&
                    isYouTubeWatchUrl(media.uri.toString())
            }.take(PROJECTIVY_YOUTUBE_DIRECT_RESOLVE_LIMIT)

        if (candidates.isEmpty()) {
            return mediaItems
        }

        val resolvedByIndex =
            supervisorScope {
                candidates.map { indexed ->
                    async(Dispatchers.IO) {
                        val watchUrl = indexed.value.uri.toString()
                        val resolvedUrl =
                            withTimeoutOrNull(YOUTUBE_PROJECTIVY_DIRECT_RESOLVE_TIMEOUT_MS) {
                                runCatching {
                                    YouTubeFeature.repository(applicationContext).preloadVideoUrl(watchUrl)
                                }.getOrNull()
                            }

                        if (resolvedUrl.isNullOrBlank() || isYouTubeWatchUrl(resolvedUrl)) {
                            null
                        } else {
                            indexed.index to resolvedUrl
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
                .toMap()

        val candidateIndexes = candidates.map { indexed -> indexed.index }.toSet()
        val unresolvedIndexes = candidateIndexes - resolvedByIndex.keys

        val patchedMedia =
            mediaItems.toMutableList().apply {
                resolvedByIndex.forEach { (index, directUrl) ->
                    this[index] =
                        this[index].copy(
                            uri = Uri.parse(directUrl),
                            streamUrl = directUrl,
                        )
                }
            }

        val filteredMedia =
            patchedMedia.filterIndexed { index, media ->
                if (!unresolvedIndexes.contains(index)) {
                    true
                } else {
                    !(media.source == AerialMediaSource.YOUTUBE &&
                        media.type == AerialMediaType.VIDEO &&
                        isYouTubeWatchUrl(media.uri.toString()))
                }
            }

        if (filteredMedia.size < MIN_PROJECTIVY_PLAYABLE_ITEMS) {
            Timber.w(
                "Projectivy YouTube direct resolve: filtered too aggressively (%s -> %s). Falling back to unresolved-safe list",
                mediaItems.size,
                filteredMedia.size,
            )
            return patchedMedia
        }

        if (resolvedByIndex.isEmpty()) {
            Timber.i(
                "Projectivy YouTube direct resolve: no direct URLs resolved from %s candidates, dropped unresolved=%s",
                candidates.size,
                mediaItems.size - filteredMedia.size,
            )
            return filteredMedia
        }

        Timber.i(
            "Projectivy YouTube direct resolve: resolved %s/%s watch URLs, dropped unresolved=%s",
            resolvedByIndex.size,
            candidates.size,
            mediaItems.size - filteredMedia.size,
        )
        return filteredMedia
    }

    private fun isYouTubeWatchUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host.orEmpty().lowercase()
        return host.contains("youtube.com") || host.contains("youtu.be")
    }

    private fun wallpaperUri(media: AerialMedia): String {
        val rawUri = media.uri.toString()
        if (media.type != AerialMediaType.VIDEO) {
            return rawUri
        }

        if (YOUTUBE_PROJECTIVY_START_SECONDS <= 0) {
            return rawUri
        }

        // Keep Projectivy intro-skip offset scoped to YouTube content.
        if (media.source != AerialMediaSource.YOUTUBE) {
            return rawUri
        }

        return if (rawUri.contains("#")) {
            "$rawUri&t=$YOUTUBE_PROJECTIVY_START_SECONDS"
        } else {
            "$rawUri#t=$YOUTUBE_PROJECTIVY_START_SECONDS"
        }
    }
}
