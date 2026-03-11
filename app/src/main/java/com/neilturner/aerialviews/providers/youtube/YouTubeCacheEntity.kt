package com.neilturner.aerialviews.providers.youtube

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtube_cache")
data class YouTubeCacheEntity(
    @PrimaryKey
    val videoId: String,
    val videoPageUrl: String,
    val streamUrl: String,
    val title: String,
    val uploaderName: String = "",
    val streamUrlExpiresAt: Long,
    val searchCachedAt: Long,
    val searchQuery: String? = null,
)
