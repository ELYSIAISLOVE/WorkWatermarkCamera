package com.watermark.camera.domain.repository

import com.watermark.camera.data.local.PhotoEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for local database operations.
 *
 * Provides a clean API over Room DAO for photo index management.
 */
interface DatabaseRepository {

    /**
     * Insert a photo record into the local index.
     *
     * @param photo The photo entity to insert.
     * @return Result indicating success or failure.
     */
    suspend fun insertPhoto(photo: PhotoEntity): Result<Unit>

    /**
     * Delete a photo record by URI.
     *
     * @param uri The photo URI.
     * @return Result indicating success or failure.
     */
    suspend fun deletePhoto(uri: String): Result<Unit>

    /**
     * Get all photos ordered by timestamp (newest first).
     *
     * @return Flow of photo list.
     */
    fun getAllPhotos(): Flow<List<PhotoEntity>>

    /**
     * Get recent photos for collage quick selection.
     *
     * @param limit Maximum number of photos to return.
     * @return List of recent photos.
     */
    suspend fun getRecentPhotos(limit: Int = 50): List<PhotoEntity>

    /**
     * Get a single photo by URI.
     *
     * @param uri The photo URI.
     * @return The photo entity, or null if not found.
     */
    suspend fun getPhotoByUri(uri: String): PhotoEntity?

    /**
     * Get the total count of indexed photos.
     *
     * @return Photo count.
     */
    suspend fun getPhotoCount(): Int

    /**
     * Clear all photo records.
     * Use with caution — does NOT delete actual image files.
     */
    suspend fun clearAll(): Result<Unit>

    /**
     * Check if a photo exists in the index.
     *
     * @param uri The photo URI.
     * @return True if the photo is indexed.
     */
    suspend fun exists(uri: String): Boolean

    // ---- 相册分页 / 搜索 / 批量删除（2026-11 相册重做新增）----

    /** 按时间倒序分页。 */
    suspend fun getPage(limit: Int, offset: Int): List<PhotoEntity>

    /** 已索引照片总数（Flow，随插入删除自动更新）。 */
    fun getPhotoCountFlow(): Flow<Int>

    /** 已索引照片里最新的时间戳，用于增量导入判断。 */
    suspend fun newestTimestamp(): Long?

    /** 批量删除索引。 */
    suspend fun deletePhotos(uris: List<String>): Result<Unit>

    /** 关键字搜索（文件名 / 经纬度）。 */
    fun searchPhotos(query: String): Flow<List<PhotoEntity>>
}
