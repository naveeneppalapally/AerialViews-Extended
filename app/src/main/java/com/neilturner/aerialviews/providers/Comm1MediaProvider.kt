package com.neilturner.aerialviews.providers

import android.content.Context
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.enums.SceneType
import com.neilturner.aerialviews.models.enums.TimeOfDay
import com.neilturner.aerialviews.models.prefs.ProviderPreferences
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.models.videos.Comm1Videos
import com.neilturner.aerialviews.utils.JsonHelper.parseJson
import com.neilturner.aerialviews.utils.JsonHelper.parseJsonMap
import timber.log.Timber

class Comm1MediaProvider(
    context: Context,
    private val prefs: ProviderPreferences,
) : MediaProvider(context) {
    override val type = ProviderSourceType.REMOTE
    val metadata = mutableMapOf<String, Pair<String, Map<Int, String>>>()
    val videos = mutableListOf<AerialMedia>()
    private var lastVideoSignature: String = ""

    override val enabled: Boolean
        get() = prefs.enabled

    override suspend fun fetchTest(): String = ""

    override suspend fun fetchMedia(): List<AerialMedia> {
        val videoSignature = currentVideoSignature()
        if (metadata.isEmpty() || videos.isEmpty() || videoSignature != lastVideoSignature) {
            buildVideoAndMetadata()
            lastVideoSignature = videoSignature
        }
        return videos
    }

    override suspend fun fetchMetadata(): MutableMap<String, Pair<String, Map<Int, String>>> {
        if (metadata.isEmpty()) buildVideoAndMetadata()
        return metadata
    }

    private suspend fun buildVideoAndMetadata() {
        metadata.clear()
        videos.clear()

        val quality = prefs.quality
        val strings = parseJsonMap(context, R.raw.comm1_strings)
        val wrapper = parseJson<Comm1Videos>(context, R.raw.comm1)

        wrapper.assets?.forEach { asset ->
            val timeOfDay = TimeOfDay.fromString(asset.timeOfDay)
            val scene = SceneType.fromString(asset.scene)

            val timeOfDayMatches = prefs.timeOfDay.contains(timeOfDay.toString())
            val sceneMatches = prefs.scene.contains(scene.toString())

            if (timeOfDayMatches && sceneMatches && prefs.enabled) {
                videos.add(
                    AerialMedia(
                        asset.uriAtQuality(quality),
                        type = AerialMediaType.VIDEO,
                        source = AerialMediaSource.COMM1,
                        metadata =
                            AerialMediaMetadata(
                                timeOfDay = timeOfDay,
                                scene = scene,
                            ),
                    ),
                )
            }

            val data =
                Pair(
                    asset.description,
                    asset.pointsOfInterest.mapValues { poi ->
                        strings[poi.value] ?: asset.description
                    },
                )
            asset.allUrls().forEachIndexed { index, url ->
                metadata[url] = data
            }
        }

        Timber.i("${metadata.count()} metadata items found")
        Timber.i("${videos.count()} $quality videos found")
    }

    private fun currentVideoSignature(): String =
        buildString {
            append(prefs.enabled)
            append('|')
            append(prefs.quality)
            append('|')
            append(prefs.scene.sorted().joinToString(","))
            append('|')
            append(prefs.timeOfDay.sorted().joinToString(","))
        }
}
