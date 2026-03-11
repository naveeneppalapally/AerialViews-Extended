package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import androidx.core.net.toUri
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.MediaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        return try {
            val cacheSize = repository.getCacheSize()
            if (cacheSize < COLD_CACHE_THRESHOLD) {
                Timber.tag(TAG).d("Cache cold (%s videos), skipping YouTube for this rotation", cacheSize)
                repository.preWarmInBackground()
                return emptyList()
            }

            repository.getCachedVideos().also { entries ->
                entries.firstOrNull()?.let { firstEntry ->
                    repository.preResolveVideo(firstEntry.videoPageUrl, providerScope)
                }
            }.map(::toAerialMedia)
        } catch (exception: Exception) {
            Timber.tag(TAG).e(exception, "Failed to fetch YouTube media")
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

    private fun toAerialMedia(entry: YouTubeCacheEntity): AerialMedia =
        AerialMedia(
            uri = entry.videoPageUrl.toUri(),
            type = AerialMediaType.VIDEO,
            source = AerialMediaSource.YOUTUBE,
            metadata =
                AerialMediaMetadata(
                    shortDescription = entry.title,
                ),
        )

    companion object {
        private const val COLD_CACHE_THRESHOLD = 5
        private const val TAG = "YouTubeMedia"
    }
}
