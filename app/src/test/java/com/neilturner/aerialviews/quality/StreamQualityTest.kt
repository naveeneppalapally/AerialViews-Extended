package com.neilturner.aerialviews.quality

import com.neilturner.aerialviews.providers.youtube.NewPipeHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Comprehensive 4K TV stream quality test suite.
 *
 * Validates that the stream selection pipeline delivers premium playback quality
 * across codec capability, resolution selection, bitrate adequacy, audio quality,
 * and content filtering — the same quality dimensions that TV-embedded players
 * like Cobalt (YouTube for Smart TVs) optimise for.
 *
 * Quality dimensions covered:
 *  1. Codec selection & priority (VP9, AVC/H.264, AV1)
 *  2. Resolution selection & downgrade chain (2160p → 1440p → 1080p → 720p)
 *  3. Bitrate floor enforcement per resolution tier
 *  4. Low-quality itag rejection (144p–480p garbage streams)
 *  5. Audio codec priority (AAC/MP4A > Opus > Vorbis)
 *  6. Aspect ratio filtering (16:9 enforcement)
 *  7. Weak-bitrate 4K rejection (anti-pixelation / anti-macro-blocking)
 *  8. Multi-codec comparison at same resolution
 *  9. Content filtering for ambient-only screensaver use
 */
@DisplayName("Stream Quality – Premium 4K TV Experience")
internal class StreamQualityTest {

    // ───────────────────────────────────────────────────────
    // 1. CODEC SELECTION & PRIORITY
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Codec Selection")
    inner class CodecSelection {

        @Test
        @DisplayName("Selects VP9 at 4K when device supports it")
        fun selectsVp9At4kWhenSupported() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 20_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 266, codec = "avc1.640033", resolution = "2160p", height = 2160, bitrate = 15_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 266),
            )
            assertEquals(315, selected?.getItag(), "VP9 should be preferred over AVC at same resolution when both supported")
        }

        @Test
        @DisplayName("Falls back to AVC at 4K when VP9 decoder is unsupported")
        fun fallsBackToAvcWhenVp9Unsupported() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 20_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 266, codec = "avc1.640033", resolution = "2160p", height = 2160, bitrate = 15_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                supportedItags = setOf(266),
                unsupportedItags = setOf(315),
            )
            assertEquals(266, selected?.getItag(), "Should fall back to AVC when VP9 decoder is unsupported")
        }

        @Test
        @DisplayName("AV1 receives high penalty on TV chipsets — VP9 preferred")
        fun av1ReceivesHighPenaltyOverVp9() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 701, codec = "av01.0.12M.08", resolution = "2160p", height = 2160, bitrate = 18_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(701, 315),
            )
            assertEquals(315, selected?.getItag(), "VP9 should be selected over AV1 at same resolution due to TV codec priority")
        }

        @Test
        @DisplayName("AV1-only 4K falls back to 1440p VP9 when AV1 unsupported")
        fun av1Only4kFallsToLowerResolution() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 701, codec = "av01.0.12M.08", resolution = "2160p", height = 2160, bitrate = 18_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 308, codec = "vp09.00.50.08", resolution = "1440p", height = 1440, bitrate = 10_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(308),
                unsupportedItags = setOf(701),
            )
            assertEquals(308, selected?.getItag(), "When AV1 4K decoder is unsupported, should fall to 1440p VP9")
        }

        @Test
        @DisplayName("Prefers VP9 over AVC at 1080p even when both supported")
        fun prefersVp9OverAvcAt1080p() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 6_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 137, codec = "avc1.640028", resolution = "1080p", height = 1080, bitrate = 5_500_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 1080,
                supportedItags = setOf(248, 137),
            )
            assertEquals(248, selected?.getItag(), "VP9 should be preferred at 1080p when both codecs supported")
        }
    }

    // ───────────────────────────────────────────────────────
    // 2. RESOLUTION SELECTION & DOWNGRADE CHAIN
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Resolution Selection")
    inner class ResolutionSelection {

        @Test
        @DisplayName("Selects 2160p when targeting 4K and stream is available")
        fun selects2160pForUhdTarget() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 308, codec = "vp09.00.50.08", resolution = "1440p", height = 1440, bitrate = 10_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 308, 248),
            )
            assertEquals(315, selected?.getItag(), "Should select 2160p stream for 4K target")
        }

        @Test
        @DisplayName("Resolution downgrade chain: 2160p unavailable → selects 1440p")
        fun downgradesTo1440pWhen4kUnavailable() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 308, codec = "vp09.00.50.08", resolution = "1440p", height = 1440, bitrate = 10_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(308, 248),
            )
            assertEquals(308, selected?.getItag(), "Should downgrade to 1440p when 2160p unavailable")
        }

        @Test
        @DisplayName("Resolution downgrade chain: 2160p+1440p unavailable → selects 1080p")
        fun downgradesTo1080pWhenHigherUnavailable() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 247, codec = "vp09.00.30.08", resolution = "720p", height = 720, bitrate = 3_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(248, 247),
            )
            assertEquals(248, selected?.getItag(), "Should downgrade to 1080p when 2160p and 1440p unavailable")
        }

        @Test
        @DisplayName("Full downgrade chain: only 720p available for 1080p target")
        fun fullDowngradeTo720p() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 247, codec = "vp09.00.30.08", resolution = "720p", height = 720, bitrate = 3_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 1080,
                supportedItags = setOf(247),
            )
            assertEquals(247, selected?.getItag(), "Should fall to 720p when it's the only option within minimum height for 1080p target")
        }

        @Test
        @DisplayName("Rejects 720p as too low when targeting 4K — minimum is 1080p")
        fun rejects720pForUhdTarget() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 247, codec = "vp09.00.30.08", resolution = "720p", height = 720, bitrate = 3_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(247),
            )
            assertNull(selected, "720p must be rejected for UHD target — minimum allowed is 1080p")
        }

        @Test
        @DisplayName("Never selects a resolution higher than the target")
        fun neverExceedsTarget() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 1080,
                supportedItags = setOf(315, 248),
            )
            assertEquals(248, selected?.getItag(), "Must not select 2160p stream when targeting 1080p")
        }
    }

    // ───────────────────────────────────────────────────────
    // 3. BITRATE FLOOR ENFORCEMENT (anti-pixelation)
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bitrate Floor Enforcement")
    inner class BitrateFloors {

        @Test
        @DisplayName("Rejects weak 4K stream (below 12 Mbps floor) in favour of strong 1440p")
        fun rejectsWeak4kForStrong1440p() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 8_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 308, codec = "vp09.00.50.08", resolution = "1440p", height = 1440, bitrate = 12_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 308),
            )
            assertEquals(308, selected?.getItag(), "8 Mbps at 4K causes macro-blocking; should prefer strong 1440p")
        }

        @Test
        @DisplayName("When only weak 1440p exists, still selected as fallback over nothing")
        fun weakBitrateStillSelectedAsFallback() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 308, codec = "vp09.00.50.08", resolution = "1440p", height = 1440, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 6_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(308, 248),
            )
            assertNotNull(selected, "Should still select a stream even when bitrate is weak")
            // The algorithm prefers higher resolution tier even with weak bitrate,
            // because bitrate floor is a soft preference during ranking.
        }

        @Test
        @DisplayName("Accepts 4K stream that meets 12 Mbps floor")
        fun accepts4kAtBitrateFloor() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 12_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 248),
            )
            assertEquals(315, selected?.getItag(), "12 Mbps at 4K meets the floor and should be selected")
        }

        @Test
        @DisplayName("Accepts 4K stream well above bitrate floor (premium quality)")
        fun acceptsHighBitrate4k() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 25_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 248),
            )
            assertEquals(315, selected?.getItag(), "25 Mbps 4K VP9 is premium quality — must be selected")
        }

        @Test
        @DisplayName("Weak 1080p still selected over 720p — preferred resolution tier wins")
        fun preferredResolutionTierWinsOverBitrateFloor() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 2_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 247, codec = "vp09.00.30.08", resolution = "720p", height = 720, bitrate = 3_500_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 1080,
                supportedItags = setOf(248, 247),
            )
            // The algorithm uses bitrate floor as a soft ranking signal, not a hard rejection.
            // 1080p is in the preferred tier for a 1080p target, so it's selected even at 2 Mbps.
            assertNotNull(selected, "Must select something")
            assertEquals(248, selected?.getItag(), "Preferred resolution tier wins over bitrate floor — 1080p selected even with weak bitrate")
        }
    }

    // ───────────────────────────────────────────────────────
    // 4. LOW-QUALITY ITAG REJECTION
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Low Quality Itag Rejection")
    inner class LowQualityItags {

        @Test
        @DisplayName("Rejects itag 18 (360p combined) in favour of higher-quality stream")
        fun rejectsItag18() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 18, codec = "avc1.42001E", resolution = "360p", height = 360, bitrate = 500_000, mediaFormat = MediaFormat.MPEG_4, isVideoOnly = false),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(18, 248),
            )
            assertEquals(248, selected?.getItag(), "itag 18 (360p) must be rejected for screensaver quality")
        }

        @Test
        @DisplayName("Rejects itag 160 (144p) — worst quality available on YouTube")
        fun rejectsItag160() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 160, codec = "avc1.4d400c", resolution = "144p", height = 144, bitrate = 100_000, mediaFormat = MediaFormat.MPEG_4),
                    videoStream(itag = 247, codec = "vp09.00.30.08", resolution = "720p", height = 720, bitrate = 3_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 1080,
                supportedItags = setOf(160, 247),
            )
            assertEquals(247, selected?.getItag(), "itag 160 (144p) must never be selected — 720p preferred")
        }

        @Test
        @DisplayName("Rejects itag 133 (240p) and itag 134 (360p) garbage tiers")
        fun rejectsSubHdItags() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 133, codec = "avc1.4d4015", resolution = "240p", height = 240, bitrate = 300_000, mediaFormat = MediaFormat.MPEG_4),
                    videoStream(itag = 134, codec = "avc1.4d401e", resolution = "360p", height = 360, bitrate = 600_000, mediaFormat = MediaFormat.MPEG_4),
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(133, 134, 248),
            )
            assertEquals(248, selected?.getItag(), "Sub-HD itags (133/134) must be rejected in favour of quality streams")
        }
    }

    // ───────────────────────────────────────────────────────
    // 5. AUDIO CODEC PRIORITY
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Audio Codec Priority")
    inner class AudioCodecPriority {

        @Test
        @DisplayName("Prefers AAC/MP4A over Opus for TV compatibility")
        fun prefersAacOverOpus() {
            val selected = NewPipeHelper.selectBestAudioStreamForTest(
                listOf(
                    audioStream(itag = 251, codec = "opus", bitrate = 160_000, mediaFormat = MediaFormat.WEBMA),
                    audioStream(itag = 140, codec = "mp4a.40.2", bitrate = 128_000, mediaFormat = MediaFormat.M4A),
                ),
            )
            assertNotNull(selected, "Must select an audio stream")
            assertEquals(140, selected!!.getItag(), "AAC/MP4A preferred over Opus for TV hardware decoder compatibility")
        }

        @Test
        @DisplayName("Prefers Opus over Vorbis as second choice")
        fun prefersOpusOverVorbis() {
            val selected = NewPipeHelper.selectBestAudioStreamForTest(
                listOf(
                    audioStream(itag = 171, codec = "vorbis", bitrate = 128_000, mediaFormat = MediaFormat.WEBMA),
                    audioStream(itag = 251, codec = "opus", bitrate = 128_000, mediaFormat = MediaFormat.WEBMA),
                ),
            )
            assertNotNull(selected)
            assertEquals(251, selected!!.getItag(), "Opus preferred over Vorbis")
        }

        @Test
        @DisplayName("Breaks AAC tie by selecting higher bitrate")
        fun breaksAacTieByBitrate() {
            val selected = NewPipeHelper.selectBestAudioStreamForTest(
                listOf(
                    audioStream(itag = 139, codec = "mp4a.40.5", bitrate = 48_000, mediaFormat = MediaFormat.M4A),
                    audioStream(itag = 140, codec = "mp4a.40.2", bitrate = 128_000, mediaFormat = MediaFormat.M4A),
                ),
            )
            assertNotNull(selected)
            assertEquals(140, selected!!.getItag(), "Higher bitrate AAC stream should win the tiebreaker")
        }

        @Test
        @DisplayName("Returns null for empty audio stream list")
        fun handlesEmptyAudioStreams() {
            val selected = NewPipeHelper.selectBestAudioStreamForTest(emptyList())
            assertNull(selected, "Empty audio list should return null gracefully")
        }
    }

    // ───────────────────────────────────────────────────────
    // 6. ASPECT RATIO FILTERING (16:9 enforcement)
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Aspect Ratio Filtering")
    inner class AspectRatio {

        @Test
        @DisplayName("Accepts standard 16:9 resolutions")
        fun acceptsStandard16x9() {
            assertTrue(NewPipeHelper.hasPreferredAspectRatioForTest("3840x2160"), "3840x2160 is 16:9")
            assertTrue(NewPipeHelper.hasPreferredAspectRatioForTest("2560x1440"), "2560x1440 is 16:9")
            assertTrue(NewPipeHelper.hasPreferredAspectRatioForTest("1920x1080"), "1920x1080 is 16:9")
            assertTrue(NewPipeHelper.hasPreferredAspectRatioForTest("1280x720"), "1280x720 is 16:9")
        }

        @Test
        @DisplayName("Rejects 4:3 aspect ratios")
        fun rejects4x3() {
            assertTrue(!NewPipeHelper.hasPreferredAspectRatioForTest("1440x1080"), "1440x1080 is 4:3 — not suitable for TV")
            assertTrue(!NewPipeHelper.hasPreferredAspectRatioForTest("640x480"), "640x480 is 4:3")
        }

        @Test
        @DisplayName("Rejects 1:1 square aspect ratio")
        fun rejectsSquare() {
            assertTrue(!NewPipeHelper.hasPreferredAspectRatioForTest("1080x1080"), "Square video not suitable for TV screensaver")
        }

        @Test
        @DisplayName("Rejects 9:16 vertical/portrait aspect ratio")
        fun rejectsVertical() {
            assertTrue(!NewPipeHelper.hasPreferredAspectRatioForTest("1080x1920"), "Vertical video must be rejected for TV")
        }
    }

    // ───────────────────────────────────────────────────────
    // 7. MULTI-CODEC QUALITY COMPARISON
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multi-Codec Quality Comparison")
    inner class MultiCodecComparison {

        @Test
        @DisplayName("VP9 at lower bitrate still preferred over AVC at same resolution")
        fun vp9PreferredOverAvcEvenAtLowerBitrate() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 13_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 266, codec = "avc1.640033", resolution = "2160p", height = 2160, bitrate = 18_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 266),
            )
            assertEquals(315, selected?.getItag(), "VP9 delivers better quality per bit than AVC — should be preferred even at lower bitrate")
        }

        @Test
        @DisplayName("Three-codec comparison: VP9 wins over AVC and AV1 at 4K")
        fun threeCodecComparison() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 701, codec = "av01.0.12M.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 14_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 266, codec = "avc1.640033", resolution = "2160p", height = 2160, bitrate = 18_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                supportedItags = setOf(701, 315, 266),
            )
            assertEquals(315, selected?.getItag(), "VP9 should win the three-way codec comparison at 4K on TV devices")
        }

        @Test
        @DisplayName("Mixed resolution+codec: prefers 4K VP9 over 1080p AVC")
        fun prefers4kVp9Over1080pAvc() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 137, codec = "avc1.640028", resolution = "1080p", height = 1080, bitrate = 8_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                supportedItags = setOf(315, 137),
            )
            assertEquals(315, selected?.getItag(), "4K VP9 must be preferred over 1080p AVC when device supports it")
        }
    }

    // ───────────────────────────────────────────────────────
    // 8. CONTENT FILTERING FOR AMBIENT SCREENSAVER
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Content Filtering for Premium Ambient Experience")
    inner class ContentFiltering {

        @Test
        @DisplayName("Rejects fast-motion content (timelapse, hyperlapse)")
        fun rejectsFastMotion() {
            assertTrue(NewPipeHelper.isLikelyHumanContentForTest("4K Urban Hyperlapse City Night"), "Hyperlapse not suitable for calm screensaver")
            assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Timelapse of Sunset Over Ocean 4K"), "Timelapse not suitable for calm screensaver")
        }

        @Test
        @DisplayName("Accepts slow-paced ambient nature footage")
        fun acceptsSlowPacedAmbient() {
            assertFalse(NewPipeHelper.isLikelyHumanContentForTest("4K Alpine Lake Ambient Nature Sounds No Music"), "Ambient nature should pass filter")
            assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norwegian Fjords Aerial 4K Drone Footage"), "Aerial drone footage should pass filter")
        }

        @Test
        @DisplayName("Rejects subtitle/caption-heavy content")
        fun rejectsSubtitleContent() {
            assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Aurora Borealis with subtitles 4K"))
        }

        @Test
        @DisplayName("Rejects top-10 list content")
        fun rejectsTop10Lists() {
            assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Top 10 Most Beautiful Places in the World 4K"))
            assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Top ten best places to visit"))
        }

        @Test
        @DisplayName("Rejects vlog-style content")
        fun rejectsVlogContent() {
            assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Day in My Life 4K Vlog"))
        }
    }

    // ───────────────────────────────────────────────────────
    // 9. EDGE CASES & ROBUSTNESS
    // ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases & Robustness")
    inner class EdgeCases {

        @Test
        @DisplayName("Returns null when no streams are available")
        fun handlesEmptyStreamList() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = emptyList(),
                targetHeight = 2160,
            )
            assertNull(selected, "Empty stream list should return null gracefully")
        }

        @Test
        @DisplayName("Handles single stream correctly")
        fun handlesSingleStream() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 248, codec = "vp09.00.31.08", resolution = "1080p", height = 1080, bitrate = 5_000_000, mediaFormat = MediaFormat.WEBM),
                ),
                targetHeight = 2160,
                supportedItags = setOf(248),
            )
            assertEquals(248, selected?.getItag(), "Single available stream should be selected")
        }

        @Test
        @DisplayName("All streams unsupported with fallback disabled returns null")
        fun allUnsupportedNoFallback() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 266, codec = "avc1.640033", resolution = "2160p", height = 2160, bitrate = 15_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                allowUnsupportedFallback = false,
                unsupportedItags = setOf(315, 266),
            )
            assertNull(selected, "Should return null when all decoders are unsupported and fallback is disabled")
        }

        @Test
        @DisplayName("All streams unsupported with fallback enabled selects best available")
        fun allUnsupportedWithFallback() {
            val selected = NewPipeHelper.selectBestVideoStreamForTest(
                streams = listOf(
                    videoStream(itag = 315, codec = "vp09.00.51.08", resolution = "2160p", height = 2160, bitrate = 16_000_000, mediaFormat = MediaFormat.WEBM),
                    videoStream(itag = 266, codec = "avc1.640033", resolution = "2160p", height = 2160, bitrate = 15_000_000, mediaFormat = MediaFormat.MPEG_4),
                ),
                targetHeight = 2160,
                allowUnsupportedFallback = true,
                unsupportedItags = setOf(315, 266),
            )
            assertNotNull(selected, "Should select best available even when unsupported, with fallback enabled")
        }
    }

    // ───────────────────────────────────────────────────────
    // HELPERS
    // ───────────────────────────────────────────────────────

    private fun videoStream(
        itag: Int,
        codec: String,
        resolution: String,
        height: Int,
        bitrate: Int,
        mediaFormat: MediaFormat,
        isVideoOnly: Boolean = true,
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
            .setIsVideoOnly(isVideoOnly)
            .setResolution(resolution)
            .setItagItem(itagItem)
            .build()
    }

    private fun audioStream(
        itag: Int,
        codec: String,
        bitrate: Int,
        mediaFormat: MediaFormat,
    ): AudioStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.AUDIO, mediaFormat, codec)
        itagItem.setBitrate(bitrate)
        itagItem.setCodec(codec)

        return AudioStream.Builder()
            .setId(itag.toString())
            .setContent("https://example.com/audio/$itag", true)
            .setMediaFormat(mediaFormat)
            .setItagItem(itagItem)
            .build()
    }

}
