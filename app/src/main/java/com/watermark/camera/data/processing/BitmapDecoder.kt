package com.watermark.camera.data.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.watermark.camera.util.Logger
import java.io.ByteArrayOutputStream

/**
 * Decodes CameraX ImageProxy to Bitmap with memory-efficient sampling.
 *
 * Supports:
 * - YUV_420_888 -> JPEG -> Bitmap (CameraX default format)
 * - Rotation correction via Matrix
 * - Down-sampling for memory control (max dimension constraint)
 * - Automatic ImageProxy lifecycle management
 */
class BitmapDecoder {

    companion object {
        private const val TAG = "BitmapDecoder"
        private const val MAX_BITMAP_DIMENSION = 4096
        private const val INTERMEDIATE_QUALITY = 95
        @Volatile
        var appContext: android.content.Context? = null
    }

    /**
     * Decode ImageProxy to a corrected Bitmap.
     *
     * @param imageProxy The captured ImageProxy (will be closed automatically).
     * @param maxDimension Maximum width/height limit (0 = no limit).
     * @return Decoded and rotated Bitmap.
     */
    fun decode(
        imageProxy: ImageProxy,
        maxDimension: Int = MAX_BITMAP_DIMENSION
    ): Bitmap {
        try {
            val startTime = System.currentTimeMillis()

            // Convert YUV to JPEG byte array
            val jpegBytes = yuvToJpeg(imageProxy)

            // Decode with sampling if needed
            val bitmap = decodeSampled(jpegBytes, maxDimension)

            // Apply rotation correction
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

            // Recycle intermediate if it's a different instance
            if (rotatedBitmap !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }

            val duration = System.currentTimeMillis() - startTime
            Logger.perf(TAG, "Decode+Rotate", duration)
            Logger.i(TAG, "Decoded: ${rotatedBitmap.width}x${rotatedBitmap.height}, " +
                "rotation=${imageProxy.imageInfo.rotationDegrees}")

            return rotatedBitmap
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert YUV_420_888 ImageProxy to JPEG byte array.
     *
     * Correctly handles NV21 interleaving (V before U) regardless of
     * the source plane layout from CameraX.
     */
    private fun yuvToJpeg(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 format: YYYYYYYY VUVUVUVU...
        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave V and U for NV21 (V comes first in each pair)
        val uvSize = uSize.coerceAtMost(vSize)
        var pos = ySize
        for (i in 0 until uvSize) {
            nv21[pos++] = vBuffer.get(i)
            nv21[pos++] = uBuffer.get(i)
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        return ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                INTERMEDIATE_QUALITY,
                out
            )
            out.toByteArray()
        }
    }

    /**
     * Decode JPEG bytes with optional down-sampling to control memory.
     */
    private fun decodeSampled(jpegBytes: ByteArray, maxDimension: Int): Bitmap {
        if (maxDimension <= 0) {
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        }

        // First decode bounds only
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

        // Calculate sample size
        options.inSampleSize = calculateInSampleSize(
            options.outWidth,
            options.outHeight,
            maxDimension,
            maxDimension
        )
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IllegalStateException("Failed to decode JPEG bytes")
    }

    /**
     * Calculate power-of-2 sample size to fit within max dimensions.
     */
    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        while (width / inSampleSize > reqWidth || height / inSampleSize > reqHeight) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /**
     * Rotate bitmap by specified degrees.
     */
    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source

        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }

        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )

        if (!source.isRecycled) {
            source.recycle()
        }

        return rotated
    }

    /**
     * Decode from file path with sampling (for collage/selection).
     */
    fun decodeFile(path: String, maxDimension: Int = 2048): Bitmap {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            return decodeUriString(path, maxDimension)
        }
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        options.inSampleSize = calculateInSampleSize(
            options.outWidth,
            options.outHeight,
            maxDimension,
            maxDimension
        )
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        return BitmapFactory.decodeFile(path, options)
            ?: throw IllegalStateException("Failed to decode file: $path")
    }

    fun decodeUriString(uriString: String, maxDimension: Int = 2048): Bitmap {
        val ctx = appContext ?: throw IllegalStateException("BitmapDecoder.appContext not set")
        val uri = android.net.Uri.parse(uriString)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalStateException("Cannot open $uriString")
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("Failed to decode $uriString")
    }
}
