package com.watermark.camera.data.gallery

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把本应用拍摄的照片从 MediaStore 导入 Room 索引。
 *
 * 相册重做后以 Room 为唯一数据源（审计 C3：旧实现查不到本应用目录时会退化成
 * 「查询全机照片」，导致别家 App 的照片混进相册、还能被本应用删除）。
 * 这里只认本应用的保存目录，导入是幂等的，可以反复调用。
 */
@Singleton
class GalleryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseRepository: DatabaseRepository
) {

    companion object {
        private const val TAG = "GalleryImporter"

        /** 本应用照片所在目录关键字（与 StorageRepository 的保存路径一致）。 */
        private const val OUR_DIR = "WatermarkCamera"
    }

    /**
     * 扫描本应用目录并补齐缺失的索引。
     *
     * @return 新导入的条数。
     */
    suspend fun importOwnPhotos(): Int = withContext(Dispatchers.IO) {
        try {
            val candidates = queryOwnPhotos()
            if (candidates.isEmpty()) {
                Logger.i(TAG, "MediaStore 中未发现本应用照片")
                return@withContext 0
            }
            var imported = 0
            for (photo in candidates) {
                if (databaseRepository.exists(photo.uri)) continue
                databaseRepository.insertPhoto(photo).onSuccess { imported++ }
            }
            Logger.i(TAG, "导入 ${imported}/${candidates.size} 张照片索引")
            imported
        } catch (e: Exception) {
            Logger.e(TAG, "导入失败", e)
            0
        }
    }

    private fun queryOwnPhotos(): List<PhotoEntity> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        // Q 起 DATA 不可靠，用 RELATIVE_PATH 判断归属；Q 之前只能看 DATA
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%$OUR_DIR%")
        } else {
            projection.plus(MediaStore.Images.Media.DATA)
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf("%$OUR_DIR%")
        }

        val result = mutableListOf<PhotoEntity>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val wCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                val addedMs = if (addedCol >= 0) c.getLong(addedCol) * 1000L else 0L
                result.add(
                    PhotoEntity(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        filePath = null,
                        fileName = c.getString(nameCol) ?: "IMG_$id.jpg",
                        timestamp = if (taken > 0L) taken else addedMs,
                        width = if (wCol >= 0) c.getInt(wCol) else 0,
                        height = if (hCol >= 0) c.getInt(hCol) else 0
                    )
                )
            }
        }
        return result
    }
}