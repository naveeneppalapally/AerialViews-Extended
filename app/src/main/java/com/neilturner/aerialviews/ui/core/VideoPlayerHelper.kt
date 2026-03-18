package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.AspectRatioFrameLayout
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.ImmichAuthType
import com.neilturner.aerialviews.models.enums.LimitLongerVideos
import com.neilturner.aerialviews.models.enums.VideoScale
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.ImmichMediaPrefs
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.samba.SambaDataSourceFactory
import com.neilturner.aerialviews.providers.webdav.WebDavDataSourceFactory
import com.neilturner.aerialviews.providers.youtube.NewPipeHelper
import com.neilturner.aerialviews.services.philips.CustomRendererFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

object VideoPlayerHelper {
    private const val TEN_SECONDS = 10 * 1000

    fun toggleAudioTrack(
        player: ExoPlayer,
        disableAudio: Boolean,
    ) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disableAudio)
                .build()
    }

    fun disableTextTrack(player: ExoPlayer) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
    }

    @OptIn(UnstableApi::class)
    fun getResizeMode(
        scale: VideoScale?,
        forceCrop: Boolean = false,
    ): Int =
        if (forceCrop || scale == VideoScale.SCALE_TO_FIT_WITH_CROPPING) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

    fun getVideoScalingMode(
        scale: VideoScale?,
        forceCrop: Boolean = false,
    ): Int =
        if (forceCrop || scale == VideoScale.SCALE_TO_FIT_WITH_CROPPING) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }

    @OptIn(UnstableApi::class)
    fun buildPlayer(
        context: Context,
        prefs: GeneralPrefs,
    ): ExoPlayer {
        val parametersBuilder =
            Parameters
                .Builder()
                .setPreferredTextLanguage(null)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)

        if (prefs.enableTunneling) {
            parametersBuilder
                .setTunnelingEnabled(true)
        }

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = parametersBuilder.build()

        var rendererFactory: DefaultRenderersFactory = DefaultRenderersFactory(context)
        rendererFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (prefs.allowFallbackDecoders) {
            rendererFactory.setEnableDecoderFallback(true)
        }

        if (prefs.philipsDolbyVisionFix) {
            rendererFactory = CustomRendererFactory(context)
            rendererFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            if (prefs.allowFallbackDecoders) {
                rendererFactory.setEnableDecoderFallback(true)
            }
        }

        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    30_000, // Minimum buffer duration
                    180_000, // Maximum buffer duration
                    2_000, // Buffer before initial playback
                    5_000, // Buffer after rebuffering
                ).build()

        val player =
            ExoPlayer
                .Builder(context)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setRenderersFactory(rendererFactory)
                .build()

        player.videoScalingMode = getVideoScalingMode(prefs.videoScale)

        if (prefs.enablePlaybackLogging) {
            player.addAnalyticsListener(EventLogger())
        }

        if (!prefs.muteVideos) {
            player.volume =
                prefs.videoVolume.toFloat() / 100
        } else {
            player.volume = 0f
        }

        // https://medium.com/androiddevelopers/prep-your-tv-app-for-android-12-9a859d9bb967
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.refreshRateSwitching) {
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }

        player.setPlaybackSpeed(prefs.playbackSpeed.toFloat())
        return player
    }

    @OptIn(UnstableApi::class)
    fun setupMediaSource(
        player: ExoPlayer,
        media: AerialMedia,
        startPositionMs: Long = C.TIME_UNSET,
    ) {
        val mediaItem = MediaItem.fromUri(media.uri)
        when (media.source) {
            AerialMediaSource.SAMBA -> {
                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(SambaDataSourceFactory())
                        .createMediaSource(mediaItem)
                setMediaSourceWithOptionalStart(player, mediaSource, startPositionMs)
            }

            AerialMediaSource.RTSP -> {
                val mediaSource =
                    RtspMediaSource
                        .Factory()
                        .setDebugLoggingEnabled(true)
                        .setForceUseRtpTcp(true)
                        .createMediaSource(mediaItem)
                setMediaSourceWithOptionalStart(player, mediaSource, startPositionMs)
            }

            AerialMediaSource.IMMICH -> {
                val dataSourceFactory =
                    DefaultHttpDataSource
                        .Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())
                        .setReadTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())

                // Add necessary headers for Immich
                if (ImmichMediaPrefs.authType == ImmichAuthType.API_KEY) {
                    dataSourceFactory.setDefaultRequestProperties(
                        mapOf("X-API-Key" to ImmichMediaPrefs.apiKey),
                    )
                }

                // If SSL validation is disabled, we need to set the appropriate flags
                if (!ImmichMediaPrefs.validateSsl) {
                    System.setProperty("javax.net.ssl.trustAll", "true")
                }

                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)

                setMediaSourceWithOptionalStart(player, mediaSource, startPositionMs)
                Timber.d("Setting up Immich media source with URI: ${media.uri}")
            }

            AerialMediaSource.WEBDAV -> {
                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(WebDavDataSourceFactory())
                        .createMediaSource(mediaItem)
                setMediaSourceWithOptionalStart(player, mediaSource, startPositionMs)
            }

            AerialMediaSource.YOUTUBE -> {
                val dataSourceFactory =
                    DefaultHttpDataSource
                        .Factory()
                        .setDefaultRequestProperties(NewPipeHelper.playbackRequestHeaders())
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())
                        .setReadTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())

                val mediaSource =
                    when {
                        media.uri.toString().contains("manifest/dash", ignoreCase = true) ||
                            media.uri.toString().contains(".mpd", ignoreCase = true) ->
                            DashMediaSource
                                .Factory(dataSourceFactory)
                                .createMediaSource(mediaItem)

                        media.uri.toString().contains("manifest/hls", ignoreCase = true) ||
                            media.uri.toString().contains(".m3u8", ignoreCase = true) ->
                            HlsMediaSource
                                .Factory(dataSourceFactory)
                                .createMediaSource(mediaItem)

                        else ->
                            ProgressiveMediaSource
                                .Factory(dataSourceFactory)
                                .createMediaSource(mediaItem)
                    }

                setMediaSourceWithOptionalStart(player, mediaSource, startPositionMs)
            }

            else -> {
                if (startPositionMs > 0L) {
                    player.setMediaItem(mediaItem, startPositionMs)
                } else {
                    player.setMediaItem(mediaItem)
                }
            }
        }
    }

    private fun setMediaSourceWithOptionalStart(
        player: ExoPlayer,
        mediaSource: MediaSource,
        startPositionMs: Long,
    ) {
        if (startPositionMs > 0L) {
            player.setMediaSource(mediaSource, startPositionMs)
        } else {
            player.setMediaSource(mediaSource)
        }
    }

    fun calculatePlaybackParameters(
        player: ExoPlayer,
        media: AerialMedia,
        prefs: GeneralPrefs,
    ): Pair<Long, Long> {
        val type = media.source
        val metadataDurationMs = media.metadata.exif.durationSeconds?.toLong()?.times(1000) ?: 0L
        val effectiveDuration = if (player.duration > 0) player.duration else metadataDurationMs

        val playbackPolicy = resolvePlaybackPolicy(type, prefs)
        val maxVideoLength = playbackPolicy.maxVideoLengthMs
        val isLengthLimited = maxVideoLength >= TEN_SECONDS
        val isShortVideo = effectiveDuration < maxVideoLength

        if (type == AerialMediaSource.RTSP) {
            Timber.i("Calculating RTSP stream length...")
            val duration = if (isLengthLimited) maxVideoLength else 0
            return Pair(0, duration)
        }

        if (!isLengthLimited && playbackPolicy.randomStartEnabled) {
            Timber.i("Calculating random start position...")
            val range = GeneralPrefs.randomStartPositionRange.toInt()
            return calculateRandomStartPosition(effectiveDuration, range)
        }

        if (isShortVideo && isLengthLimited && prefs.loopShortVideos) {
            Timber.i("Calculating looping short video...")
            return calculateLoopingVideo(effectiveDuration, maxVideoLength)
        }

        if (!isShortVideo && isLengthLimited) {
            when (playbackPolicy.limitMode) {
                LimitLongerVideos.LIMIT -> {
                    Timber.i("Calculating long video type... obey limit, play until time limit")
                    val duration =
                        if (maxVideoLength >= effectiveDuration) {
                            Timber.i("Using video duration as limit (shorter than max!)")
                            effectiveDuration
                        } else {
                            Timber.i("Using user limit")
                            maxVideoLength
                        }
                    return Pair(0, duration)
                }

                LimitLongerVideos.SEGMENT -> {
                    Timber.i("Calculating long video type... play random segment")
                    return calculateRandomSegment(effectiveDuration, maxVideoLength)
                }

                else -> {
                    Timber.i("Calculating long video type... ignore limit, play full video")
                    return Pair(0, effectiveDuration)
                }
            }
        }

        // Use normal start + end/duration
        Timber.i("Calculating normal video type...")
        return Pair(0, effectiveDuration)
    }

    private fun resolvePlaybackPolicy(
        mediaSource: AerialMediaSource,
        prefs: GeneralPrefs,
    ): PlaybackPolicy {
        if (mediaSource != AerialMediaSource.YOUTUBE) {
            return PlaybackPolicy(
                maxVideoLengthMs = prefs.maxVideoLength.toLongOrNull()?.times(1000L) ?: 0L,
                limitMode = prefs.limitLongerVideos,
                randomStartEnabled = prefs.randomStartPosition,
            )
        }

        val youtubeMode = YouTubeVideoPrefs.playbackLengthMode.trim().lowercase()
        val youtubeMaxLengthMs = YouTubeVideoPrefs.playbackMaxMinutes.toLong() * 60L * 1000L
        return when (youtubeMode) {
            "full" ->
                PlaybackPolicy(
                    maxVideoLengthMs = 0L,
                    limitMode = LimitLongerVideos.IGNORE,
                    randomStartEnabled = false,
                )
            "segment" ->
                PlaybackPolicy(
                    maxVideoLengthMs = youtubeMaxLengthMs,
                    limitMode = LimitLongerVideos.SEGMENT,
                    randomStartEnabled = false,
                )
            else ->
                PlaybackPolicy(
                    maxVideoLengthMs = youtubeMaxLengthMs,
                    limitMode = LimitLongerVideos.LIMIT,
                    randomStartEnabled = false,
                )
        }
    }

    private data class PlaybackPolicy(
        val maxVideoLengthMs: Long,
        val limitMode: LimitLongerVideos?,
        val randomStartEnabled: Boolean,
    )

    private fun calculateRandomStartPosition(
        duration: Long,
        range: Int,
    ): Pair<Long, Long> {
        if (duration <= 0 || range < 5) {
            Timber.e("Invalid duration or range: duration=$duration, range=$range%")
            return Pair(0, 0)
        }
        val seekPosition = (duration * range / 100.0).toLong()
        val randomPosition = Random.nextLong(seekPosition)

        val percent = (randomPosition.toFloat() / duration.toFloat() * 100).toInt()
        Timber.i("Start at ${randomPosition.milliseconds} ($percent%, from 0%-%$range)")

        return Pair(randomPosition, duration)
    }

    private fun calculateRandomSegment(
        duration: Long,
        maxLength: Long,
    ): Pair<Long, Long> {
        if (duration <= 0 || maxLength < TEN_SECONDS) {
            Timber.e("Invalid duration or max length: duration=$duration, maxLength=$maxLength%")
        }

        val numOfSegments = duration / maxLength
        if (numOfSegments < 2) {
            Timber.i("Video too short for segments")
            return Pair(0, duration)
        }

        val length = duration.floorDiv(numOfSegments)
        val randomSegment = (1..numOfSegments).random()
        val segmentStart = (randomSegment - 1) * length
        val segmentEnd = randomSegment * length

        val message1 =
            "Video length ${duration.milliseconds}, $numOfSegments segments of ${length.milliseconds}\n"
        val message2 =
            "Chose segment $randomSegment, ${segmentStart.milliseconds} - ${segmentEnd.milliseconds}"
        Timber.i("$message1$message2")

        return Pair(segmentStart, segmentEnd)
    }

    private fun calculateLoopingVideo(
        duration: Long,
        maxLength: Long,
    ): Pair<Long, Long> {
        if (duration <= 0 || maxLength < TEN_SECONDS) {
            Timber.e("Invalid duration or max length: duration=$duration, maxLength=$maxLength%")
            return Pair(0, 0)
        }
        val loopCount = ceil(maxLength / duration.toDouble()).toInt()
        val targetDuration = duration * loopCount
        Timber.i(
            "Looping $loopCount times (video is ${duration.milliseconds}, total is ${targetDuration.milliseconds}, limit is ${maxLength.milliseconds})",
        )
        return Pair(0, targetDuration)
    }
}
