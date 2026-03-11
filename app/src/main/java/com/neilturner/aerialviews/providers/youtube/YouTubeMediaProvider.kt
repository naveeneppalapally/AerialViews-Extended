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
import timber.log.Timber

class YouTubeMediaProvider(
    context: Context,
) : MediaProvider(context) {
    private val repository by lazy { YouTubeFeature.repository(context) }

    override val type = ProviderSourceType.REMOTE

    override val enabled: Boolean
        get() = YouTubeVideoPrefs.enabled

    override suspend fun fetchMedia(): List<AerialMedia> {
        return try {
            repository.getCachedVideos().map { entry ->
                AerialMedia(
                    uri = entry.videoPageUrl.toUri(),
                    type = AerialMediaType.VIDEO,
                    source = AerialMediaSource.YOUTUBE,
                    metadata =
                        AerialMediaMetadata(
                            shortDescription = entry.title,
                        ),
                )
            }
        } catch (exception: Exception) {
            Timber.e(exception, "Failed to fetch YouTube media")
            emptyList()
        }
    }

    override suspend fun fetchTest(): String {
        return runCatching {
            val refreshedCount = repository.forceRefresh()
            "Refreshed $refreshedCount videos"
        }.getOrElse { exception ->
            Timber.e(exception, "Failed to refresh YouTube media")
            "Refresh failed: ${exception.localizedMessage ?: "Unknown error"}"
        }
    }

    override suspend fun fetchMetadata(): MutableMap<String, Pair<String, Map<Int, String>>> = mutableMapOf()
}
