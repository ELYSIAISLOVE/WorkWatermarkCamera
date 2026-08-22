package com.watermark.camera.data.repository

class StorageRepositoryImpl {

    suspend fun save(
        data: ByteArray,
        fileName: String
    ): Result<String> {
        return runCatching {
            fileName
        }
    }
}
