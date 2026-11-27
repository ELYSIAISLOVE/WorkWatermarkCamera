package com.watermark.camera.data.processing

import android.graphics.Bitmap
import com.watermark.camera.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Encodes Bitmap to JPEG with memory-efficient streaming.
 *
 * Features:
 * - Configurable quality (default 95 for photos)
 * - ByteArray and File output modes
 * - Automatic bitmap recycling option
 */
class BitmapEncoder {

    companion object {
        private const val TAG = "BitmapEncoder"
        private const val DEFAULT_QUALITY = 95
        private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64KB
    }

    /**
     * Encode bitmap to JPEG byte array.
     *
     * @param bitmap Source bitmap.
     * @param quality JPEG quality 0-100.
     * @param recycleSource Whether to recycle source after encoding.
     * @return JPEG byte array.
     */
    fun encodeToBytes(
        bitmap: Bitmap,
        quality: Int = DEFAULT_QUALITY,
        recycleSource: Boolean = false
    ): ByteArray {
        val startTime = System.currentTimeMillis()

        val stream = ByteArrayOutputStream(STREAM_BUFFER_SIZE)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        stream.close()

        if (recycleSource && !bitmap.isRecycled) {
            bitmap.recycle()
        }

        val duration = System.currentTimeMillis() - startTime
        Logger.perf(TAG, "Encode to bytes", duration)
        Logger.i(TAG, "Encoded ${bitmap.width}x${bitmap.height} -> ${bytes.size / 1024}KB")

        return bytes
    }

    /**
     * Encode bitmap directly to file.
     *
     * More memory-efficient than encodeToBytes + write.
     *
     * @param bitmap Source bitmap.
     * @param file Destination file.
     * @param quality JPEG quality 0-100.
     * @param recycleSource Whether to recycle source after encoding.
     */
    fun encodeToFile(
        bitmap: Bitmap,
        file: File,
        quality: Int = DEFAULT_QUALITY,
        recycleSource: Boolean = false
    ) {
        val startTime = System.currentTimeMillis()

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.flush()
        }

        if (recycleSource && !bitmap.isRecycled) {
            bitmap.recycle()
        }

        val duration = System.currentTimeMillis() - startTime
        Logger.perf(TAG, "Encode to file", duration)
        Logger.i(TAG, "Saved ${bitmap.width}x${bitmap.height} to ${file.name}")
    }
}
