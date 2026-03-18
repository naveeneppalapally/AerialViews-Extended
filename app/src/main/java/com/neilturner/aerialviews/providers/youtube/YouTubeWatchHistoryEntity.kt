package com.neilturner.aerialviews.providers.youtube

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtube_watch_history")
data class YouTubeWatchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val historyId: Long = 0L,
    val videoId: String,
    val playedAt: Long,
)
