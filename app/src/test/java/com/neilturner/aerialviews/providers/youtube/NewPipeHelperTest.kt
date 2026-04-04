package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.VideoStream

@DisplayName("NewPipe Helper Tests")
internal class NewPipeHelperTest {
    @Test
    @DisplayName("Should treat dramatic pipe-separated titles as human content")
    fun testDramaticPipeSeparatedTitlesRejected() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Snow Leopard fight Mountain Goat | Wildlife Documentary"))
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Unbelievable | Cliff Chase | Amazing footage"))
    }

    @Test
    @DisplayName("Should allow ambient pipe-separated titles")
    fun testAmbientPipeSeparatedTitlesAllowed() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Japan 4K | Nature Walk | Ambient Sounds"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norway Fjords | Aerial 4K | No Music"))
    }

    @Test
    @DisplayName("Should reject documentary-style titles")
    fun testDocumentaryTitleRejected() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Wildlife Documentary Animals | Nature Film"))
    }

    @Test
    @DisplayName("Should allow ambient titles through filter")
    fun testAmbientTitlesAllowed() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("4K Japan Forest Walk Ambient No Music"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norwegian Fjords Aerial Drone 4K"))
    }

    @Test
    @DisplayName("Should prefer same-resolution AVC when 4K VP9 is unsupported")
    fun testSameResolutionCodecFallbackPreferred() {
        val selected =
            NewPipeHelper.selectBestVideoStreamForTest(
                streams =
                    listOf(
                        videoStream(
                            itag = 401,
                            codec = "vp09.00.51.08",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 15_000_000,
                            mediaFormat = MediaFormat.WEBM,
                        ),
                        videoStream(
                            itag = 266,
                            codec = "avc1.640033",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 9_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                        videoStream(
                            itag = 137,
                            codec = "avc1.640028",
                            resolution = "1080p",
                            height = 1080,
                            bitrate = 6_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                    ),
                targetHeight = 2160,
                supportedItags = setOf(266, 137),
                unsupportedItags = setOf(401),
            )

        assertEquals(266, selected?.getItag())
    }

    @Test
    @DisplayName("Should fall back to lower supported codec when same-resolution fallback is absent")
    fun testLowerResolutionFallbackWhenNeeded() {
        val selected =
            NewPipeHelper.selectBestVideoStreamForTest(
                streams =
                    listOf(
                        videoStream(
                            itag = 401,
                            codec = "vp09.00.51.08",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 15_000_000,
                            mediaFormat = MediaFormat.WEBM,
                        ),
                        videoStream(
                            itag = 137,
                            codec = "avc1.640028",
                            resolution = "1080p",
                            height = 1080,
                            bitrate = 6_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                    ),
                targetHeight = 2160,
                supportedItags = setOf(137),
                unsupportedItags = setOf(401),
            )

        assertEquals(137, selected?.getItag())
    }

    private fun videoStream(
        itag: Int,
        codec: String,
        resolution: String,
        height: Int,
        bitrate: Int,
        mediaFormat: MediaFormat,
    ): VideoStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.VIDEO_ONLY, mediaFormat, resolution)
        itagItem.setWidth((height * 16f / 9f).toInt())
        itagItem.setHeight(height)
        itagItem.setBitrate(bitrate)
        itagItem.setQuality(resolution)
        itagItem.setCodec(codec)

        return VideoStream.Builder()
            .setId(itag.toString())
            .setContent("https://example.com/$itag", true)
            .setMediaFormat(mediaFormat)
            .setIsVideoOnly(true)
            .setResolution(resolution)
            .setItagItem(itagItem)
            .build()
    }
}