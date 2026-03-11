package com.neilturner.aerialviews.providers.youtube

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface YouTubeCacheDao {
    @Query("SELECT * FROM youtube_cache ORDER BY title COLLATE NOCASE ASC")
    fun getAll(): List<YouTubeCacheEntity>

    @Query("SELECT * FROM youtube_cache WHERE streamUrlExpiresAt > :now ORDER BY title COLLATE NOCASE ASC")
    fun getValidEntries(now: Long): List<YouTubeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<YouTubeCacheEntity>)

    @Query("DELETE FROM youtube_cache")
    fun clearAll()

    @Transaction
    fun clearAndInsert(entries: List<YouTubeCacheEntity>) {
        clearAll()
        insertAll(entries)
    }

    @Query(
        "UPDATE youtube_cache SET streamUrl = :newUrl, streamUrlExpiresAt = :newExpiresAt " +
            "WHERE videoId = :videoId",
    )
    fun updateStreamUrl(
        videoId: String,
        newUrl: String,
        newExpiresAt: Long,
    )

    @Query("SELECT MIN(searchCachedAt) FROM youtube_cache")
    fun getOldestCachedAt(): Long?

    @Query("SELECT * FROM youtube_cache WHERE videoPageUrl = :videoPageUrl LIMIT 1")
    fun getByVideoPageUrl(videoPageUrl: String): YouTubeCacheEntity?
}
