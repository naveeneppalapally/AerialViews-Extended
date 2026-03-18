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

    @Query("SELECT * FROM youtube_cache WHERE isBad = 0 ORDER BY title COLLATE NOCASE ASC")
    fun getAllGood(): List<YouTubeCacheEntity>

    @Query("SELECT COUNT(*) FROM youtube_cache WHERE isBad = 0")
    fun countGoodEntries(): Int

    @Query("SELECT * FROM youtube_cache WHERE isBad = 0 AND streamUrlExpiresAt > :now ORDER BY title COLLATE NOCASE ASC")
    fun getValidEntries(now: Long): List<YouTubeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<YouTubeCacheEntity>)

    @Query("DELETE FROM youtube_cache")
    fun clearAll()

    @Query("DELETE FROM youtube_cache WHERE isBad = 0")
    fun clearAllGood()

    @Transaction
    fun clearAndInsert(entries: List<YouTubeCacheEntity>) {
        clearAll()
        insertAll(entries)
    }

    @Query(
        "UPDATE youtube_cache SET streamUrl = :newUrl, streamUrlExpiresAt = :newExpiresAt, isBad = 0 " +
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

    @Query("UPDATE youtube_cache SET isBad = 1 WHERE videoId = :videoId AND isBad = 0")
    fun markAsBad(videoId: String): Int

    @Query("UPDATE youtube_cache SET lastPlayedAt = :timestamp WHERE videoId = :videoId")
    fun markAsPlayed(
        videoId: String,
        timestamp: Long,
    )

    @Query("UPDATE youtube_cache SET lastPlayedAt = 0")
    fun resetPlayHistory()

    @Query(
        "DELETE FROM youtube_cache " +
            "WHERE isBad = 0 AND categoryKey IS NOT NULL AND categoryKey != '' AND categoryKey NOT IN (:allowedCategoryKeys)",
    )
    fun deleteByNotInCategories(allowedCategoryKeys: List<String>): Int

    @Query("DELETE FROM youtube_cache WHERE videoId IN (:videoIds)")
    fun deleteByVideoIds(videoIds: List<String>): Int

    @Query(
        "SELECT * FROM youtube_cache " +
            "WHERE isBad = 0 AND (lastPlayedAt = 0 OR lastPlayedAt < :cutoff) " +
            "ORDER BY RANDOM() LIMIT 1",
    )
    fun getUnwatchedEntry(cutoff: Long): YouTubeCacheEntity?

    @Query("SELECT * FROM youtube_cache WHERE isBad = 0 ORDER BY lastPlayedAt ASC LIMIT 1")
    fun getLeastRecentlyPlayed(): YouTubeCacheEntity?
}
