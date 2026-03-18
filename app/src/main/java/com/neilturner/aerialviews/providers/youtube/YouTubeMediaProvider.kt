package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.utils.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class YouTubeMediaProvider(
    context: Context,
) : MediaProvider(context) {
    private val repository by lazy { YouTubeFeature.repository(context) }
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val type = ProviderSourceType.REMOTE

    override val enabled: Boolean
        get() = YouTubeVideoPrefs.enabled

    override suspend fun fetchMedia(): List<AerialMedia> {
        val cacheSize = repository.getCacheSize()
        Log.i(TAG, "YouTube fetchMedia startup cacheSize=$cacheSize")
        if (cacheSize == 0) {
            return fetchInitialMedia()
        }

        // Startup must always prefer immediately playable local cache entries.
        // Signature/version refresh can run in background, but should not block initial playback.
        val startupCachedEntries = repository.getCachedVideosSnapshot()
        if (startupCachedEntries.isNotEmpty()) {
            Log.i(TAG, "Using local startup cache entries=${startupCachedEntries.size}")
            repository.preWarmInBackground()
            return startupCachedEntries.toAerialMedia()
        }

        return withTimeoutOrNull(NORMAL_FETCH_TIMEOUT_MS) {
            fetchCachedMedia()
        } ?: run {
            Timber.tag(TAG).w("fetchMedia timed out after %sms, skipping YouTube slot", NORMAL_FETCH_TIMEOUT_MS)
            emptyList()
        }
    }

    override suspend fun fetchTest(): String {
        return runCatching {
            val refreshedCount = repository.forceRefresh()
            "Refreshed $refreshedCount videos"
        }.getOrElse { exception ->
            Timber.tag(TAG).e(exception, "Failed to refresh YouTube media")
            "Refresh failed: ${exception.localizedMessage ?: "Unknown error"}"
        }
    }

    override suspend fun fetchMetadata(): MutableMap<String, Pair<String, Map<Int, String>>> = mutableMapOf()

    private suspend fun fetchInitialMedia(): List<AerialMedia> {
        YouTubeFeature.markCountPending()

        if (!NetworkHelper.isInternetAvailable(context)) {
            Timber.tag(TAG).w("Cache empty and network unavailable for first-run YouTube fetch")
            return emptyList()
        }

        val warmedCacheEntries =
            withTimeoutOrNull(INITIAL_CACHE_WARM_WAIT_MS) {
                repository.getCachedVideosSnapshot()
            } ?: emptyList()
        if (warmedCacheEntries.isNotEmpty()) {
            Log.i(TAG, "Using warmed startup cache entries=${warmedCacheEntries.size}")
            repository.preWarmInBackground()
            return warmedCacheEntries.toAerialMedia()
        }

        val bootstrapMedia = buildBootstrapMedia()
        if (bootstrapMedia.isEmpty()) {
            return emptyList()
        }

        val firstBootstrapUrl = bootstrapMedia.first().uri.toString()
        repository.preResolveVideo(firstBootstrapUrl, providerScope)
        Log.i(TAG, "Startup bootstrap first URL queued for pre-resolve")
        Log.i(TAG, "Using bootstrap startup playlist size=${bootstrapMedia.size}")
        providerScope.launch {
            delay(STARTUP_BACKGROUND_WARM_DELAY_MS)
            repository.preWarmInBackground()
        }
        return bootstrapMedia
    }

    private suspend fun fetchCachedMedia(): List<AerialMedia> {
        return try {
            repository.getLocalCachedVideos().toAerialMedia()
        } catch (exception: Exception) {
            Timber.tag(TAG).w(exception, "fetchMedia failed")
            emptyList()
        }
    }

    private fun List<YouTubeCacheEntity>.toAerialMedia(): List<AerialMedia> {
        if (isEmpty()) {
            return emptyList()
        }

        firstOrNull()?.let { firstEntry ->
            if (repository.playbackUrl(firstEntry) == firstEntry.videoPageUrl) {
                repository.preResolveVideo(firstEntry.videoPageUrl, providerScope)
            } else {
                repository.preResolveNext(providerScope)
            }
        }

        val directPlaybackCount =
            count { entry ->
                repository.playbackUrl(entry) != entry.videoPageUrl
            }
        Timber.tag(TAG).i(
            "Preparing %s cached YouTube playlist items (freshDirect=%s, directWindow=%s)",
            size,
            directPlaybackCount,
            DIRECT_PLAYBACK_WINDOW,
        )

        return mapIndexed { index, entry ->
            toAerialMedia(
                entry = entry,
                useDirectPlaybackUrl = index < DIRECT_PLAYBACK_WINDOW,
            )
        }
    }

    private fun buildBootstrapMedia(): MutableList<AerialMedia> =
        STARTUP_BOOTSTRAP_VIDEO_URLS.shuffled().map { videoPageUrl ->
            val videoId = extractBootstrapVideoId(videoPageUrl)
            AerialMedia(
                uri = videoPageUrl.toUri(),
                type = AerialMediaType.VIDEO,
                source = AerialMediaSource.YOUTUBE,
                metadata =
                    AerialMediaMetadata(
                        shortDescription = "YouTube Startup Bootstrap",
                        exif =
                            AerialExifMetadata(
                                description = videoId,
                                durationSeconds = 0,
                            ),
                    ),
            )
        }.toMutableList()

    private fun extractBootstrapVideoId(videoPageUrl: String): String =
        Regex("[?&]v=([^&#]+)")
            .find(videoPageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: videoPageUrl

    private fun toAerialMedia(
        entry: YouTubeCacheEntity,
        useDirectPlaybackUrl: Boolean,
    ): AerialMedia {
        val playbackUrl = if (useDirectPlaybackUrl) {
            repository.playbackUrl(entry)
        } else {
            entry.videoPageUrl
        }
        
        return AerialMedia(
            uri = playbackUrl.toUri(),
            type = AerialMediaType.VIDEO,
            source = AerialMediaSource.YOUTUBE,
            streamUrl = if (playbackUrl != entry.videoPageUrl) playbackUrl else "",
            metadata =
                AerialMediaMetadata(
                    shortDescription = entry.title,
                    exif =
                        AerialExifMetadata(
                            description = entry.videoId,
                            durationSeconds = entry.durationSeconds,
                        ),
                ),
        )
    }

    companion object {
        private const val INITIAL_CACHE_WARM_WAIT_MS = 1_500L
        private const val STARTUP_BACKGROUND_WARM_DELAY_MS = 30_000L
        private const val NORMAL_FETCH_TIMEOUT_MS = 3_000L
        private const val DIRECT_PLAYBACK_WINDOW = 12
        private const val TAG = "YouTubeMedia"
        private val STARTUP_BOOTSTRAP_VIDEO_URLS =
            listOf(
                "https://www.youtube.com/watch?v=BHACKCNDMW8",
                "https://www.youtube.com/watch?v=LXb3EKWsInQ",
            )
    }
}
