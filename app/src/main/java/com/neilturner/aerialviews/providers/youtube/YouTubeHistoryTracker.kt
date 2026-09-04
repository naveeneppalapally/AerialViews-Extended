package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.ArrayDeque

/**
 * Owns all playback/refresh history: the watch-history table plus the
 * SharedPreferences mirrors (play/theme/recent-refresh histories, last
 * channel, first-launch state). Previously these reads/writes were scattered
 * across the repository with two sources of truth; now there is one owner.
 */
class YouTubeHistoryTracker(
    private val cacheDao: YouTubeCacheDao,
    private val watchHistoryDao: YouTubeWatchHistoryDao,
    private val sharedPreferences: SharedPreferences,
) {
    suspend fun playHistory(): ArrayDeque<String> {
        val dbHistory = watchHistoryDao.recentHistory(PlaylistOrderer.MAX_PLAY_HISTORY)
        if (dbHistory.isNotEmpty()) {
            return ArrayDeque(dbHistory.asReversed().map { it.videoId })
        }
        return readHistory(KEY_PLAY_HISTORY)
    }

    fun themeHistory(): ArrayDeque<String> = readHistory(KEY_THEME_HISTORY)

    fun recentRefreshIds(): ArrayDeque<String> = readHistory(KEY_RECENT_REFRESH_IDS)

    fun recordRefreshHistory(entries: List<YouTubeCacheEntity>) {
        val history = recentRefreshIds()
        entries.forEach { entry ->
            history.remove(entry.videoId)
            history.addLast(entry.videoId)
        }

        while (history.size > MAX_RECENT_REFRESH_IDS) {
            history.removeFirst()
        }

        writeHistory(KEY_RECENT_REFRESH_IDS, history)
    }

    fun lastPlayedChannel(): String =
        sharedPreferences.getString(KEY_LAST_CHANNEL, "")?.trim().orEmpty()

    fun isFirstLaunchActive(): Boolean =
        sharedPreferences.getBoolean(KEY_FIRST_LAUNCH, true)

    fun firstLaunchIndex(): Int =
        sharedPreferences.getInt(KEY_FIRST_LAUNCH_INDEX, 0)

    suspend fun recordPlayback(entry: YouTubeCacheEntity) {
        val playedAt = System.currentTimeMillis()
        cacheDao.markAsPlayed(entry.videoId, playedAt)
        watchHistoryDao.insert(
            YouTubeWatchHistoryEntity(
                videoId = entry.videoId,
                playedAt = playedAt,
            ),
        )
        watchHistoryDao.trimToLimit(MAX_WATCH_HISTORY_ROWS)

        val history = playHistory()
        history.addLast(entry.videoId)
        PlaylistOrderer.trimHistory(history, PlaylistOrderer.MAX_PLAY_HISTORY)

        val themes = themeHistory()
        val theme = PlaylistOrderer.detectTheme(entry.title)
        themes.addLast(theme)
        PlaylistOrderer.trimHistory(themes, PlaylistOrderer.MAX_THEME_HISTORY)

        val firstLaunchStillActive = isFirstLaunchActive()
        val nextFirstLaunchIndex =
            if (firstLaunchStillActive) {
                (firstLaunchIndex() + 1).coerceAtMost(PlaylistOrderer.FIRST_LAUNCH_SEQUENCE.size)
            } else {
                firstLaunchIndex()
            }

        sharedPreferences.edit {
            putString(KEY_PLAY_HISTORY, history.joinToString(HISTORY_SEPARATOR))
            putString(KEY_THEME_HISTORY, themes.joinToString(HISTORY_SEPARATOR))
            putString(KEY_LAST_CATEGORY, entry.searchQuery.orEmpty())
            putString(KEY_LAST_CHANNEL, entry.uploaderName)
            putInt(KEY_FIRST_LAUNCH_INDEX, nextFirstLaunchIndex)
            putBoolean(KEY_FIRST_LAUNCH, nextFirstLaunchIndex < PlaylistOrderer.FIRST_LAUNCH_SEQUENCE.size)
        }
    }

    suspend fun prunePlayHistory(cachedEntries: List<YouTubeCacheEntity>) {
        if (cachedEntries.isEmpty()) {
            return
        }
        // Drop watch-history rows outside the 7-day repeat window so ancient
        // plays stop excluding candidates from refresh discovery forever.
        // Count-based trimming already happens on record; this is the time bound.
        runCatching {
            watchHistoryDao.deleteOlderThan(System.currentTimeMillis() - RECENT_PLAYBACK_WINDOW_MS)
        }
    }

    private fun readHistory(key: String): ArrayDeque<String> {
        val rawHistory = sharedPreferences.getString(key, "").orEmpty()
        val parsedHistory =
            rawHistory
                .split(HISTORY_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotBlank)
        return ArrayDeque(parsedHistory)
    }

    private fun writeHistory(
        key: String,
        values: ArrayDeque<String>,
    ) {
        sharedPreferences.edit {
            putString(key, values.joinToString(HISTORY_SEPARATOR))
        }
    }

    companion object {
        const val KEY_PLAY_HISTORY = "yt_play_history"
        const val KEY_LAST_CATEGORY = "yt_last_category"
        const val KEY_THEME_HISTORY = "yt_theme_history"
        const val KEY_LAST_CHANNEL = "yt_last_channel"
        const val KEY_FIRST_LAUNCH = "yt_first_launch"
        const val KEY_FIRST_LAUNCH_INDEX = "yt_first_launch_index"
        const val KEY_RECENT_REFRESH_IDS = "yt_recent_refresh_ids"
        const val RECENT_PLAYBACK_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        const val HISTORY_SEPARATOR = "|"
        private const val MAX_WATCH_HISTORY_ROWS = 5_000
        private const val MAX_RECENT_REFRESH_IDS = 960
    }
}
