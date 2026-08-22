package com.watermark.camera.data.processing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageProcessingPipeline(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun process(
        input: ByteArray,
        processor: suspend (ByteArray) -> ByteArray
    ): ByteArray = withContext(dispatcher) {
        processor(input)
    }
}
