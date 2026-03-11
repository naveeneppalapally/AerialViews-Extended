package com.neilturner.aerialviews.services

import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.videos.AerialMedia
import io.mockk.mockk
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MediaServiceHelperTest {
    @Test
    fun `youtube equal weight keeps mixed sources visible early`() {
        val media = buildMixedMedia()

        val ordered = MediaServiceHelper.applyYouTubeMixWeight(media, youtubeWeight = 1, random = Random(7))
        val firstSixSources = ordered.take(6).map { it.source }

        assertEquals(2, firstSixSources.count { it == AerialMediaSource.YOUTUBE })
        assertEquals(2, firstSixSources.count { it == AerialMediaSource.APPLE })
        assertEquals(2, firstSixSources.count { it == AerialMediaSource.AMAZON })
    }

    @Test
    fun `youtube higher weight increases youtube frequency without hiding other sources`() {
        val media = buildMixedMedia()

        val equalMix = MediaServiceHelper.applyYouTubeMixWeight(media, youtubeWeight = 1, random = Random(7))
        val heavierMix = MediaServiceHelper.applyYouTubeMixWeight(media, youtubeWeight = 3, random = Random(7))

        assertTrue(
            heavierMix.take(5).count { it.source == AerialMediaSource.YOUTUBE } >
                equalMix.take(5).count { it.source == AerialMediaSource.YOUTUBE },
        )
        assertTrue(heavierMix.take(5).any { it.source == AerialMediaSource.APPLE })
        assertTrue(heavierMix.take(5).any { it.source == AerialMediaSource.AMAZON })
    }

    private fun buildMixedMedia(): List<AerialMedia> =
        buildList {
            repeat(6) { index ->
                add(media("youtube-$index", AerialMediaSource.YOUTUBE))
            }
            repeat(2) { index ->
                add(media("apple-$index", AerialMediaSource.APPLE))
            }
            repeat(2) { index ->
                add(media("amazon-$index", AerialMediaSource.AMAZON))
            }
        }

    private fun media(
        id: String,
        source: AerialMediaSource,
    ): AerialMedia =
        AerialMedia(
            uri = mockk(relaxed = true),
            type = AerialMediaType.VIDEO,
            source = source,
        )
}
