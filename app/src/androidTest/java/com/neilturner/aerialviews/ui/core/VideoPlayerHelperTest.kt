package com.neilturner.aerialviews.ui.core

import androidx.core.net.toUri
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.videos.AerialMedia
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPlayerHelperTest {
    @Test
    fun youtubeMediaSourceMergesSeparateAudioStreams() {
        val media =
            AerialMedia(
                uri = "https://example.com/video.mp4".toUri(),
                source = AerialMediaSource.YOUTUBE,
                streamUrl = "https://example.com/video.mp4",
                audioStreamUrl = "https://example.com/audio.m4a",
            )

        val mediaSource = buildYouTubeMediaSource(media)

        assertTrue(
            "Expected YouTube media with separate audioStreamUrl to use MergingMediaSource, got ${mediaSource.javaClass.name}",
            mediaSource is MergingMediaSource,
        )
    }

    @Test
    fun youtubeMediaSourceKeepsDashWhenNoSeparateAudioExists() {
        val media =
            AerialMedia(
                uri = "https://example.com/manifest/dash/video.mpd".toUri(),
                source = AerialMediaSource.YOUTUBE,
            )

        val mediaSource = buildYouTubeMediaSource(media)

        assertTrue(
            "Expected DASH media without separate audio to stay DASH, got ${mediaSource.javaClass.name}",
            mediaSource is DashMediaSource,
        )
    }

    @Test
    fun youtubeMediaSourceUsesProgressiveForDirectVideoOnlyStream() {
        val media =
            AerialMedia(
                uri = "https://example.com/video.mp4".toUri(),
                source = AerialMediaSource.YOUTUBE,
            )

        val mediaSource = buildYouTubeMediaSource(media)

        assertTrue(
            "Expected direct YouTube media without separate audio to use ProgressiveMediaSource, got ${mediaSource.javaClass.name}",
            mediaSource is ProgressiveMediaSource,
        )
    }

    private fun buildYouTubeMediaSource(media: AerialMedia): MediaSource {
        val method =
            VideoPlayerHelper::class.java.getDeclaredMethod(
                "buildYouTubeMediaSource",
                AerialMedia::class.java,
                DefaultHttpDataSource.Factory::class.java,
            )
        method.isAccessible = true
        return method.invoke(VideoPlayerHelper, media, DefaultHttpDataSource.Factory()) as MediaSource
    }
}