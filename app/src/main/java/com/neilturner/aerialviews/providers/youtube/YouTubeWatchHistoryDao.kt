package com.neilturner.aerialviews.providers.youtube

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface YouTubeWatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entry: YouTubeWatchHistoryEntity)

    @Query(
        "SELECT * FROM youtube_watch_history " +
            "ORDER BY playedAt DESC, historyId DESC LIMIT :limit",
    )
    fun recentHistory(limit: Int): List<YouTubeWatchHistoryEntity>

    @Query(
        "DELETE FROM youtube_watch_history " +
            "WHERE historyId NOT IN (" +
            "SELECT historyId FROM youtube_watch_history ORDER BY playedAt DESC, historyId DESC LIMIT :limit" +
            ")",
    )
    fun trimToLimit(limit: Int)
}
