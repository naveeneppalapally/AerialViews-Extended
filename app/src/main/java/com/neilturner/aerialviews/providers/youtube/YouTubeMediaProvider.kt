package com.neilturner.aerialviews.providers.youtube

import android.content.Context
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
        if (cacheSize == 0) {
            return fetchInitialMedia()
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

        if (!YouTubeFeature.isOnlyYouTubeSourceEnabled()) {
            Timber.tag(TAG).d("Cache empty, warming YouTube in background while other sources play")
            repository.preWarmInBackground()
            return emptyList()
        }

        if (!NetworkHelper.isInternetAvailable(context)) {
            Timber.tag(TAG).w("Cache empty and network unavailable for first-run YouTube fetch")
            return emptyList()
        }

        Timber.tag(TAG).d("Cache empty on first run, bootstrapping YouTube cache")
        val refreshedEntries =
            withTimeoutOrNull(INITIAL_FETCH_TIMEOUT_MS) {
                runCatching { repository.refreshSearchResults() }
                    .onFailure { exception ->
                        Timber.tag(TAG).w(exception, "Initial YouTube refresh failed")
                    }.getOrDefault(emptyList())
            }

        if (refreshedEntries == null) {
            Timber.tag(TAG).w("Initial YouTube fetch timed out after %sms", INITIAL_FETCH_TIMEOUT_MS)
            repository.preWarmInBackground()
        }

        return fetchCachedMedia()
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

        return mapIndexed { index, entry ->
            toAerialMedia(
                entry = entry,
                useDirectPlaybackUrl = index == 0,
            )
        }
    }

    private fun toAerialMedia(
        entry: YouTubeCacheEntity,
        useDirectPlaybackUrl: Boolean,
    ): AerialMedia =
        AerialMedia(
            uri =
                if (useDirectPlaybackUrl) {
                    repository.playbackUrl(entry).toUri()
                } else {
                    entry.videoPageUrl.toUri()
                },
            type = AerialMediaType.VIDEO,
            source = AerialMediaSource.YOUTUBE,
            metadata =
                AerialMediaMetadata(
                    shortDescription = entry.title,
                    exif =
                        AerialExifMetadata(
                            description = entry.videoId,
                        ),
                ),
        )

    companion object {
        private const val INITIAL_FETCH_TIMEOUT_MS = 30_000L
        private const val NORMAL_FETCH_TIMEOUT_MS = 3_000L
        private const val TAG = "YouTubeMedia"
    }
}
