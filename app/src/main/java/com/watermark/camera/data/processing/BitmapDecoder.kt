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
import java.nio.ByteBuffer

/**
 * Decodes CameraX [ImageProxy] to [Bitmap].
 *
 * Handles:
 * - JPEG single-plane captures (planes.size == 1) — common on many devices
 * - YUV_420_888 three-plane captures
 * - Safe plane access (never touch index >= planes.size)
 */
class BitmapDecoder {

    companion object {
        private const val TAG = "BitmapDecoder"
        private const val MAX_BITMAP_DIMENSION = 4096
        private const val INTERMEDIATE_QUALITY = 95

        @Volatile
        var appContext: android.content.Context? = null
    }

    fun decode(
        imageProxy: ImageProxy,
        maxDimension: Int = MAX_BITMAP_DIMENSION
    ): Bitmap {
        try {
            val start = System.currentTimeMillis()
            val jpegBytes = imageProxyToJpeg(imageProxy)
            val bitmap = decodeSampled(jpegBytes, maxDimension)
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            Logger.i(TAG, "Decoded ${rotated.width}x${rotated.height} in ${System.currentTimeMillis() - start}ms")
            return rotated
        } finally {
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    /**
     * Convert ImageProxy to JPEG bytes without assuming 3 planes.
     */
    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray {
        val planes = imageProxy.planes
        val format = imageProxy.format
        Logger.d(TAG, "format=$format planes=${planes.size} ${imageProxy.width}x${imageProxy.height}")

        // JPEG buffer (single plane) — fixes "length=1; index=1"
        if (planes.isEmpty()) {
            throw IllegalStateException("ImageProxy has no planes")
        }
        if (planes.size == 1 || format == ImageFormat.JPEG) {
            return planeToByteArray(planes[0].buffer)
        }

        // YUV_420_888 needs at least 3 planes
        if (planes.size >= 3) {
            return yuv420ToJpeg(imageProxy)
        }

        // Fallback: treat first plane as compressed data
        Logger.w(TAG, "Unexpected format=$format planes=${planes.size}, using plane[0]")
        return planeToByteArray(planes[0].buffer)
    }

    private fun planeToByteArray(buffer: ByteBuffer): ByteArray {
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun yuv420ToJpeg(imageProxy: ImageProxy): ByteArray {
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val width = imageProxy.width
        val height = imageProxy.height

        // Build NV21
        val nv21 = ByteArray(width * height * 3 / 2)
        // Y
        yBuffer.get(nv21, 0, ySize.coerceAtMost(width * height))

        // VU interleaved — use row strides carefully when possible
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        var pos = width * height
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        if (vPixelStride == 1 && uPixelStride == 1) {
            // Contiguous-ish: interleave manually with remaining
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val pairs = minOf(uSize, vSize, nv21.size - pos)
            for (i in 0 until pairs / 2) {
                if (pos + 1 >= nv21.size) break
                nv21[pos++] = vBuffer.get(i)
                nv21[pos++] = uBuffer.get(i)
            }
        } else {
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    if (pos + 1 >= nv21.size) break
                    val vIndex = row * vRowStride + col * vPixelStride
                    val uIndex = row * uRowStride + col * uPixelStride
                    if (vIndex < vBuffer.capacity() && uIndex < uBuffer.capacity()) {
                        nv21[pos++] = vBuffer.get(vIndex)
                        nv21[pos++] = uBuffer.get(uIndex)
                    }
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(Rect(0, 0, width, height), INTERMEDIATE_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun decodeSampled(jpegBytes: ByteArray, maxDimension: Int): Bitmap {
        if (jpegBytes.isEmpty()) throw IllegalStateException("Empty image bytes")
        if (maxDimension <= 0) {
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: throw IllegalStateException("Failed to decode JPEG")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
            ?: throw IllegalStateException("Failed to decode JPEG")
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (w <= 0 || h <= 0) return 1
        while (w / inSampleSize > reqW || h / inSampleSize > reqH) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (!source.isRecycled) source.recycle()
        return rotated
    }

    fun decodeFile(path: String, maxDimension: Int = 2048): Bitmap {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            return decodeUriString(path, maxDimension)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
            ?: throw IllegalStateException("Failed to decode file: $path")
    }

    fun decodeUriString(uriString: String, maxDimension: Int = 2048): Bitmap {
        val ctx = appContext ?: throw IllegalStateException("BitmapDecoder.appContext not set")
        val uri = android.net.Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalStateException("Cannot open $uriString")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("Failed to decode $uriString")
    }
}
