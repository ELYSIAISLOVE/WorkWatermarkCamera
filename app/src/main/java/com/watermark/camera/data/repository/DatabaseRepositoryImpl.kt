package com.watermark.camera.data.repository

import com.watermark.camera.data.local.PhotoDao
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of DatabaseRepository using Room.
 */
@Singleton
class DatabaseRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao
) : DatabaseRepository {

    companion object {
        private const val TAG = "DatabaseRepo"
    }

    override suspend fun insertPhoto(photo: PhotoEntity): Result<Unit> {
        return try {
            photoDao.insert(photo)
            Logger.i(TAG, "Photo indexed: ${photo.uri}")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to index photo", e)
            Result.failure(e)
        }
    }

    override suspend fun deletePhoto(uri: String): Result<Unit> {
        return try {
            photoDao.deleteByUri(uri)
            Logger.i(TAG, "Photo removed from index: $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete photo index", e)
            Result.failure(e)
        }
    }

    override fun getAllPhotos(): Flow<List<PhotoEntity>> {
        return photoDao.getAllPhotos()
    }

    override suspend fun getRecentPhotos(limit: Int): List<PhotoEntity> {
        return photoDao.getRecentPhotos(limit)
    }

    override suspend fun getPhotoByUri(uri: String): PhotoEntity? {
        return photoDao.getPhotoByUri(uri)
    }

    override suspend fun getPhotoCount(): Int {
        return photoDao.getPhotoCount()
    }

    override suspend fun clearAll(): Result<Unit> {
        return try {
            photoDao.deleteAll()
            Logger.w(TAG, "All photo indexes cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clear indexes", e)
            Result.failure(e)
        }
    }

    override suspend fun exists(uri: String): Boolean {
        return photoDao.getPhotoByUri(uri) != null
    }
}
