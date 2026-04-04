package com.neilturner.aerialviews.testing

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.neilturner.aerialviews.providers.youtube.YouTubeCacheDatabase
import com.neilturner.aerialviews.providers.youtube.YouTubeCacheEntity
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository

object YouTubeInstrumentationFixtures {
    private const val KEY_SOURCE_MODE = "source_mode"
    private const val SOURCE_MODE_YOUTUBE = "youtube"
    private const val KEY_SHUFFLE_VIDEOS = "shuffle_videos"
    private const val KEY_QUALITY_INITIALIZED = "yt_quality_initialized"
    private const val KEY_QUALITY_USER_SELECTED = "yt_quality_user_selected"

    private const val KEY_APPLE_ENABLED = "apple_videos_enabled"
    private const val KEY_AMAZON_ENABLED = "amazon_videos_enabled"
    private const val KEY_COMM1_ENABLED = "comm1_videos_enabled"
    private const val KEY_COMM2_ENABLED = "comm2_videos_enabled"
    private const val KEY_LOCAL_ENABLED = "local_videos_enabled"
    private const val KEY_SAMBA_ENABLED = "samba_videos_enabled"
    private const val KEY_SAMBA2_ENABLED = "samba_videos2_enabled"
    private const val KEY_WEBDAV_ENABLED = "webdav_media_enabled"
    private const val KEY_WEBDAV2_ENABLED = "webdav_media2_enabled"
    private const val KEY_IMMICH_ENABLED = "immich_media_enabled"
    private const val KEY_CUSTOM_ENABLED = "custom_media_enabled"

    private const val KEY_PROJECTIVY_SHARED_PROVIDERS = "projectivy_shared_providers"
    private const val KEY_PROJECTIVY_SHUFFLE_VIDEOS = "projectivy_shuffle_videos"

    private const val PROJECTIVY_STREAM_HOST = "rr1---sn.example.googlevideo.com"
    private const val PLAYABLE_STREAM_URL =
        "http://10.0.2.2:18080/BigBuckBunny.mp4"

    fun resetAppState(
        database: YouTubeCacheDatabase,
        prefs: SharedPreferences,
    ) {
        database.clearAllTables()
        prefs.edit {
            clear()
        }
    }

    fun configureYouTubeOnlyPlayback(
        context: Context,
        prefs: SharedPreferences,
        entryCount: Int,
        quality: String = "1080p",
    ) {
        prefs.edit {
            putString(KEY_SOURCE_MODE, SOURCE_MODE_YOUTUBE)
            putBoolean(KEY_SHUFFLE_VIDEOS, false)

            putBoolean(YouTubeSourceRepository.KEY_ENABLED, true)
            putBoolean(YouTubeSourceRepository.KEY_SHUFFLE, false)
            putString(YouTubeSourceRepository.KEY_QUALITY, quality)
            putString(YouTubeSourceRepository.KEY_MIX_WEIGHT, YouTubeSourceRepository.DEFAULT_MIX_WEIGHT)
            putBoolean(KEY_QUALITY_INITIALIZED, true)
            putBoolean(KEY_QUALITY_USER_SELECTED, true)
            putBoolean(YouTubeSourceRepository.KEY_FIRST_LAUNCH, false)
            putInt(YouTubeSourceRepository.KEY_FIRST_LAUNCH_INDEX, 0)
            putInt(YouTubeSourceRepository.KEY_CACHE_VERSION, currentCacheVersion())
            putString(YouTubeSourceRepository.KEY_CACHE_SIGNATURE, currentCacheSignature(context))
            putString(YouTubeSourceRepository.KEY_COUNT, entryCount.toString())

            putBoolean(KEY_APPLE_ENABLED, false)
            putBoolean(KEY_AMAZON_ENABLED, false)
            putBoolean(KEY_COMM1_ENABLED, false)
            putBoolean(KEY_COMM2_ENABLED, false)
            putBoolean(KEY_LOCAL_ENABLED, false)
            putBoolean(KEY_SAMBA_ENABLED, false)
            putBoolean(KEY_SAMBA2_ENABLED, false)
            putBoolean(KEY_WEBDAV_ENABLED, false)
            putBoolean(KEY_WEBDAV2_ENABLED, false)
            putBoolean(KEY_IMMICH_ENABLED, false)
            putBoolean(KEY_CUSTOM_ENABLED, false)
        }
    }

    fun configureProjectivyForYouTubeOnly(
        context: Context,
        prefs: SharedPreferences,
        entryCount: Int,
    ) {
        configureYouTubeOnlyPlayback(
            context = context,
            prefs = prefs,
            entryCount = entryCount,
        )
        prefs.edit {
            putStringSet(KEY_PROJECTIVY_SHARED_PROVIDERS, setOf("youtube"))
            putBoolean(KEY_PROJECTIVY_SHUFFLE_VIDEOS, false)
        }
    }

    fun seedPlayableYouTubeCache(
        database: YouTubeCacheDatabase,
        entryCount: Int,
    ) {
        val now = System.currentTimeMillis()
        database.youtubeCacheDao().insertAll(
            (1..entryCount).map { index ->
                val paddedIndex = index.toString().padStart(4, '0')
                YouTubeCacheEntity(
                    videoId = "playable$paddedIndex",
                    videoPageUrl = "https://www.youtube.com/watch?v=playable$paddedIndex",
                    streamUrl = "$PLAYABLE_STREAM_URL#video=playable$paddedIndex",
                    title = "Playable YouTube Video $paddedIndex",
                    uploaderName = "playable-channel-$paddedIndex",
                    durationSeconds = 30,
                    categoryKey = "nature",
                    streamUrlExpiresAt = now + 86_400_000L,
                    searchCachedAt = now,
                    searchQuery = "4K aerial nature ambient",
                    isBad = false,
                    lastPlayedAt = 0L,
                )
            },
        )
    }

    fun seedProjectivyYouTubeCache(
        database: YouTubeCacheDatabase,
        entryCount: Int,
    ) {
        val now = System.currentTimeMillis()
        database.youtubeCacheDao().insertAll(
            (1..entryCount).map { index ->
                val paddedIndex = index.toString().padStart(4, '0')
                YouTubeCacheEntity(
                    videoId = "projectivy$paddedIndex",
                    videoPageUrl = "https://www.youtube.com/watch?v=projectivy$paddedIndex",
                    streamUrl = "https://$PROJECTIVY_STREAM_HOST/videoplayback?id=projectivy$paddedIndex.mp4&itag=137",
                    title = "Ambient Projectivy Video $paddedIndex",
                    uploaderName = "projectivy-channel-$paddedIndex",
                    durationSeconds = 600,
                    categoryKey = "nature",
                    streamUrlExpiresAt = now + 86_400_000L,
                    searchCachedAt = now,
                    searchQuery = "4K ambient nature",
                    isBad = false,
                    lastPlayedAt = 0L,
                )
            },
        )
    }

    private fun currentCacheSignature(context: Context): String {
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        return "$versionCode|v${currentCacheVersion()}"
    }

    private fun currentCacheVersion(): Int {
        runCatching {
            val field = YouTubeSourceRepository::class.java.getDeclaredField("CURRENT_CACHE_VERSION")
            field.isAccessible = true
            return field.getInt(null)
        }
        val companionClass =
            YouTubeSourceRepository::class.java.declaredClasses.firstOrNull { declaredClass ->
                declaredClass.simpleName == "Companion"
            } ?: error("Unable to locate YouTubeSourceRepository companion")
        val field = companionClass.getDeclaredField("CURRENT_CACHE_VERSION")
        field.isAccessible = true
        return field.getInt(null)
    }
}