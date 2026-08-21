package com.watermark.camera.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for photo index records.
 */
@Dao
interface PhotoDao {

    /**
     * Inserts a photo record. Replaces on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity)

    /**
     * Deletes a photo record.
     */
    @Delete
    suspend fun delete(photo: PhotoEntity)

    /**
     * Deletes a photo record by URI.
     */
    @Query("DELETE FROM photos WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    /**
     * Gets all photos ordered by timestamp descending (newest first).
     */
    @Query("SELECT * FROM photos ORDER BY timestamp DESC")
    fun getAllPhotos(): Flow<List<PhotoEntity>>

    /**
     * Gets recent photos (last N records).
     */
    @Query("SELECT * FROM photos ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentPhotos(limit: Int): List<PhotoEntity>

    /**
     * Gets a photo by its URI.
     */
    @Query("SELECT * FROM photos WHERE uri = :uri LIMIT 1")
    suspend fun getPhotoByUri(uri: String): PhotoEntity?

    /**
     * Gets the count of all photos.
     */
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun getPhotoCount(): Int

    /**
     * Deletes all records.
     */
    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}
