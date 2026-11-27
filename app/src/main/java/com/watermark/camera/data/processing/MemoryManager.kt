package com.watermark.camera.data.processing

import android.graphics.Bitmap
import com.watermark.camera.util.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Memory manager for image processing pipeline.
 *
 * Monitors heap usage and provides:
 * - Peak memory tracking
 * - Bitmap recycling enforcement
 * - Emergency GC trigger when approaching limit
 * - Reusable bitmap pool for intermediate operations
 */
class MemoryManager {

    companion object {
        private const val TAG = "MemoryManager"

        // Memory limits (bytes)
        private const val MAX_HEAP_USAGE = 200 * 1024 * 1024L // 200MB per PRD
        private const val WARNING_THRESHOLD = 150 * 1024 * 1024L // 150MB warning
        private const val EMERGENCY_THRESHOLD = 180 * 1024 * 1024L // 180MB emergency

        // Bitmap pool limits
        private const val MAX_POOL_SIZE = 3
    }

    private val peakMemory = AtomicLong(0)
    private val bitmapPool = ConcurrentLinkedQueue<Bitmap>()
    private var currentProcessingId: String? = null

    /**
     * Check memory before starting an operation.
     *
     * @throws OutOfMemoryError if memory is critically low.
     */
    fun checkMemory(operationId: String) {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()

        updatePeak(used)
        currentProcessingId = operationId

        Logger.i(TAG, "Memory check [$operationId]: used=${used / 1024 / 1024}MB, " +
            "max=${max / 1024 / 1024}MB, peak=${peakMemory.get() / 1024 / 1024}MB")

        when {
            used > EMERGENCY_THRESHOLD -> {
                performEmergencyCleanup()
                val afterUsed = runtime.totalMemory() - runtime.freeMemory()
                if (afterUsed > MAX_HEAP_USAGE) {
                    throw OutOfMemoryError(
                        "Memory critical: ${afterUsed / 1024 / 1024}MB / ${MAX_HEAP_USAGE / 1024 / 1024}MB"
                    )
                }
            }
            used > WARNING_THRESHOLD -> {
                Logger.w(TAG, "Memory warning: ${used / 1024 / 1024}MB")
                suggestGc()
            }
        }
    }

    /**
     * Track peak memory usage.
     */
    private fun updatePeak(used: Long) {
        var current: Long
        do {
            current = peakMemory.get()
            if (used <= current) return
        } while (!peakMemory.compareAndSet(current, used))
    }

    /**
     * Get peak memory usage in bytes.
     */
    fun getPeakMemory(): Long = peakMemory.get()

    /**
     * Reset peak tracking for new session.
     */
    fun resetPeak() {
        peakMemory.set(0)
        bitmapPool.clear()
    }

    /**
     * Safely recycle a bitmap.
     */
    fun recycle(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
                Logger.d(TAG, "Recycled bitmap ${it.width}x${it.height}")
            }
        }
    }

    /**
     * Recycle multiple bitmaps.
     */
    fun recycleAll(bitmaps: List<Bitmap?>) {
        bitmaps.forEach { recycle(it) }
    }

    /**
     * Try to get a reusable bitmap from pool.
     */
    fun obtainBitmap(width: Int, height: Int): Bitmap? {
        val iterator = bitmapPool.iterator()
        while (iterator.hasNext()) {
            val pooled = iterator.next()
            if (pooled.width == width && pooled.height == height && !pooled.isRecycled) {
                iterator.remove()
                Logger.d(TAG, "Reused bitmap from pool: ${width}x${height}")
                return pooled
            }
        }
        return null
    }

    /**
     * Return a bitmap to the pool for reuse.
     */
    fun releaseToPool(bitmap: Bitmap?) {
        bitmap ?: return
        if (bitmap.isRecycled) return
        if (bitmapPool.size >= MAX_POOL_SIZE) {
            recycle(bitmap)
            return
        }
        bitmapPool.offer(bitmap)
        Logger.d(TAG, "Bitmap returned to pool, size=${bitmapPool.size}")
    }

    /**
     * Clear the bitmap pool.
     */
    fun clearPool() {
        bitmapPool.forEach { recycle(it) }
        bitmapPool.clear()
    }

    /**
     * Perform emergency cleanup: clear pool, suggest GC.
     */
    private fun performEmergencyCleanup() {
        Logger.w(TAG, "Emergency memory cleanup triggered")
        clearPool()
        suggestGc()
    }

    /**
     * Suggest garbage collection (best effort).
     */
    private fun suggestGc() {
        System.gc()
        System.runFinalization()
        System.gc()
    }

    /**
     * Estimate memory for a bitmap of given dimensions.
     */
    fun estimateMemory(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Long {
        val bytesPerPixel = when (config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.ALPHA_8 -> 1
            else -> 4
        }
        return width.toLong() * height * bytesPerPixel
    }
}
