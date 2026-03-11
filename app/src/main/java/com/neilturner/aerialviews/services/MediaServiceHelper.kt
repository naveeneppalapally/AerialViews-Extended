package com.neilturner.aerialviews.services

import com.neilturner.aerialviews.models.enums.DescriptionFilenameType
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.utils.FileHelper
import com.neilturner.aerialviews.utils.filenameWithoutExtension
import com.neilturner.aerialviews.utils.parallelForEach
import timber.log.Timber
import kotlin.random.Random
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal object MediaServiceHelper {
    suspend fun addMetadataToManifestVideos(
        media: List<AerialMedia>,
        providers: List<MediaProvider>,
    ): Pair<List<AerialMedia>, List<AerialMedia>> {
        val metadata = ConcurrentHashMap<String, Pair<String, Map<Int, String>>>()
        val matched = CopyOnWriteArrayList<AerialMedia>()
        val unmatched = CopyOnWriteArrayList<AerialMedia>()

        providers.forEach {
            try {
                metadata.putAll(it.fetchMetadata())
            } catch (ex: Exception) {
                Timber.e(ex, "Exception while fetching metadata")
            }
        }

        media.parallelForEach { video ->
            val data = metadata[video.uri.filenameWithoutExtension.lowercase()]
            if (data != null) {
                video.metadata.shortDescription = data.first
                video.metadata.pointsOfInterest = data.second
                matched.add(video)
            } else {
                unmatched.add(video)
            }
        }

        return Pair(matched, unmatched)
    }

    suspend fun buildMediaList(providers: List<MediaProvider>): List<AerialMedia> {
        val media = CopyOnWriteArrayList<AerialMedia>()

        providers
            .filter { it.enabled }
            .parallelForEach {
                try {
                    it.prepare()
                    val providerMedia = it.fetchMedia()
                    media.addAll(providerMedia)
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception while fetching media from ${it.type}")
                    // FirebaseHelper.logExceptionIfRecent(ex)
                }
        }
        return media
    }

    fun applyYouTubeMixWeight(
        media: List<AerialMedia>,
        youtubeWeight: Int,
        random: Random = Random.Default,
    ): List<AerialMedia> {
        if (media.isEmpty()) {
            return emptyList()
        }

        val normalizedWeight = youtubeWeight.coerceIn(MIN_YOUTUBE_WEIGHT, MAX_YOUTUBE_WEIGHT)
        val hasYouTube = media.any { it.source == AerialMediaSource.YOUTUBE }
        val hasOtherSources = media.any { it.source != AerialMediaSource.YOUTUBE }
        if (!hasYouTube || !hasOtherSources) {
            return media.shuffled(random)
        }

        val buckets = linkedMapOf<AerialMediaSource, ArrayDeque<AerialMedia>>()
        media.shuffled(random).forEach { item ->
            buckets.getOrPut(item.source) { ArrayDeque() }.addLast(item)
        }

        val weightedSources =
            buckets.keys.flatMap { source ->
                List(if (source == AerialMediaSource.YOUTUBE) normalizedWeight else 1) { source }
            }

        val mixedMedia = mutableListOf<AerialMedia>()
        while (mixedMedia.size < media.size) {
            var addedInCycle = false
            weightedSources.shuffled(random).forEach { source ->
                val bucket = buckets[source] ?: return@forEach
                val nextItem = bucket.pollFirst() ?: return@forEach
                mixedMedia += nextItem
                addedInCycle = true
            }

            if (!addedInCycle) {
                break
            }
        }

        return mixedMedia
    }

    private const val MIN_YOUTUBE_WEIGHT = 1
    private const val MAX_YOUTUBE_WEIGHT = 3
}
