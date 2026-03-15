package com.neilturner.aerialviews.ui

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.neilturner.aerialviews.providers.youtube.NewPipeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class YouTubeTestActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null

    // A known good 4K nature video - BBC Earth
    private val defaultTestVideoUrl = "https://www.youtube.com/watch?v=LXb3EKWsInQ"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv =
            TextView(this).apply {
                text = "YouTube Test\nExtracting stream URL...\nCheck logcat"
                textSize = 24f
                setPadding(60, 60, 60, 60)
            }
        setContentView(tv)

        scope.launch {
            val testVideoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: defaultTestVideoUrl
            tv.text = "Step 1: Initializing NewPipe..."
            Log.i(TAG, "=== YouTube Test Started ===")
            Log.i(TAG, "Using test URL: $testVideoUrl")

            try {
                withContext(Dispatchers.IO) {
                    NewPipeHelper.init()
                }
                Log.i(TAG, "NewPipe init: OK")
                tv.text = "Step 1 OK: NewPipe initialized\nStep 2: Extracting stream URL..."

                val streamUrl =
                    withContext(Dispatchers.IO) {
                        NewPipeHelper.extractStreamUrl(testVideoUrl, this@YouTubeTestActivity)
                    }

                if (streamUrl.isBlank()) {
                    Log.e(TAG, "Stream URL is null or blank")
                    tv.text = "FAILED: Stream URL is null\nCheck logcat for errors"
                    return@launch
                }

                Log.i(TAG, "Stream URL extracted: ${streamUrl.take(80)}...")
                tv.text = "Step 2 OK: Got stream URL\n${streamUrl.take(60)}...\n\nStep 3: Starting ExoPlayer..."

                val videoView = PlayerView(this@YouTubeTestActivity)
                setContentView(videoView)

                player =
                    ExoPlayer.Builder(this@YouTubeTestActivity)
                        .build()
                        .also { exoPlayer ->
                            videoView.player = exoPlayer
                            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        }

                Log.i(TAG, "ExoPlayer started with stream URL")
            } catch (e: Exception) {
                Log.e(TAG, "Test failed: ${e.javaClass.simpleName}: ${e.message}", e)
                tv.text = "FAILED: ${e.javaClass.simpleName}\n${e.message}\n\nCheck logcat"
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "video_url"
        private const val TAG = "YouTubeTest"
    }
}
