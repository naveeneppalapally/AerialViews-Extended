package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [YouTubeCacheEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class YouTubeCacheDatabase : RoomDatabase() {
    abstract fun youtubeCacheDao(): YouTubeCacheDao

    companion object {
        private const val DATABASE_NAME = "youtube_cache.db"
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN searchQuery TEXT",
                    )
                }
            }
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN uploaderName TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN isBad INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        @Volatile
        private var instance: YouTubeCacheDatabase? = null

        fun getInstance(context: Context): YouTubeCacheDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room
                            .databaseBuilder(
                                context.applicationContext,
                                YouTubeCacheDatabase::class.java,
                                DATABASE_NAME,
                            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build()
                            .also { instance = it }
                }
    }
}
