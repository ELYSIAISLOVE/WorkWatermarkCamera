package com.watermark.camera.data.repository

class CameraRepositoryImpl {

    suspend fun capture(): Result<ByteArray> {
        return Result.success(ByteArray(0))
    }
}
