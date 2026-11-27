package com.watermark.camera.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the application.
 *
 * Stores:
 * - Photo index records for quick access in collage feature
 */
@Database(
    entities = [PhotoEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Provides access to photo DAO.
     */
    abstract fun photoDao(): PhotoDao
}
