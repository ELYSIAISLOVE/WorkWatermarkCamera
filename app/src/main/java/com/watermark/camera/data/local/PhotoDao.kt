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
     * 按时间倒序分页读取（相册分页加载用）。
     */
    @Query("SELECT * FROM photos ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<PhotoEntity>

    /**
     * 页面总数（分页时判断是否还有更多）。
     */
    @Query("SELECT COUNT(*) FROM photos")
    fun countFlow(): Flow<Int>

    /**
     * 照片里最早/最晚的时间，用于一次性 MediaStore 导入的增量判断。
     */
    @Query("SELECT MAX(timestamp) FROM photos")
    suspend fun newestTimestamp(): Long?

    /**
     * 批量删除（多选删除用，比逐条 delete 少一次事务往返）。
     */
    @Query("DELETE FROM photos WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /**
     * 关键字搜索：文件名 / 经纬度字符串（相册搜索用）。
     */
    @Query(
        "SELECT * FROM photos WHERE fileName LIKE :q " +
            "OR CAST(latitude AS TEXT) LIKE :q OR CAST(longitude AS TEXT) LIKE :q " +
            "ORDER BY timestamp DESC"
    )
    fun search(q: String): Flow<List<PhotoEntity>>

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
