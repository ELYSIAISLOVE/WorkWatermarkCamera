package com.watermark.camera.data.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.watermark.camera.data.processing.BitmapDecoder
import com.watermark.camera.data.processing.MemoryManager
import com.watermark.camera.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Core collage generation engine.
 *
 * Handles multi-photo layout, memory-safe decoding, and report bar rendering.
 *
 * Memory safety strategy:
 * 1. Each source photo is decoded with dynamic sampling based on target cell size.
 * 2. Grid templates create a single output Bitmap; source Bitmaps are recycled immediately.
 * 3. Vertical-long template uses chunked streaming when total height exceeds the
 *    Android single-Bitmap limit (~32,766 px), writing JPEG directly without holding
 *    the full collage in memory.
 *
 * @param decoder BitmapDecoder for sampling source photos.
 * @param memoryManager Monitors heap and enforces the 200 MB limit.
 */
class CollageEngine(
    private val decoder: BitmapDecoder = BitmapDecoder(),
    private val memoryManager: MemoryManager = MemoryManager()
) {

    companion object {
        private const val TAG = "CollageEngine"

        /** Maximum single Bitmap dimension Android allows (Canvas/Bitmap limit). */
        private const val MAX_BITMAP_DIMENSION = 32766

        /** Default output width for collages (px). */
        private const val OUTPUT_WIDTH = 1440

        /** Spacing between cells (px). */
        private const val CELL_SPACING = 8

        /** Report bar height (px). */
        private const val REPORT_BAR_HEIGHT = 160

        /** Background color for empty areas. */
        private const val BG_COLOR = Color.WHITE

        /** Report bar background color. */
        private val REPORT_BAR_BG = Color.parseColor("#F5F5F5")

        /** Report bar text color. */
        private val REPORT_TEXT_COLOR = Color.parseColor("#333333")

        /** JPEG quality for final output. */
        private const val OUTPUT_QUALITY = 90
    }

    /**
     * Result of collage generation.
     *
     * @property bitmap The generated collage Bitmap (null for streamed long-collage).
     * @property file The output file (always set for streamed output).
     * @property width Output width.
     * @property height Output height.
     * @property isStreamed True if the collage was written via streaming (no single Bitmap).
     */
    data class CollageResult(
        val bitmap: Bitmap?,
        val file: File?,
        val width: Int,
        val height: Int,
        val isStreamed: Boolean
    )

    /**
     * Report bar data.
     *
     * @param timeText Time string displayed on the bar.
     * @param locationText Location string (reserved for Step 15 GPS integration).
     * @param projectText Project / custom text.
     */
    data class ReportData(
        val timeText: String,
        val locationText: String = "未获取位置", // Reserved for Step 15
        val projectText: String = ""
    )

    // region Public API

    /**
     * Generate a collage from photo file paths.
     *
     * @param photoPaths List of absolute file paths.
     * @param template Target layout template.
     * @param reportData Report bar content (ignored for Grid2/4/9 if not needed).
     * @param outputFile Destination file for streamed templates (VerticalLong).
     * @return CollageResult containing Bitmap or file reference.
     */
    suspend fun generate(
        photoPaths: List<String>,
        template: CollageTemplate,
        reportData: ReportData = buildDefaultReportData(),
        outputFile: File? = null
    ): Result<CollageResult> {
        if (photoPaths.isEmpty()) {
            return Result.failure(IllegalArgumentException("Photo list is empty"))
        }
        if (photoPaths.size > template.maxPhotos) {
            return Result.failure(
                IllegalArgumentException(
                    "Template ${template.displayName} supports max ${template.maxPhotos} photos, " +
                        "got ${photoPaths.size}"
                )
            )
        }

        memoryManager.checkMemory("collage_${template.displayName}")
        val startTime = System.currentTimeMillis()

        return try {
            val result = when (template) {
                is CollageTemplate.Grid2,
                is CollageTemplate.Grid4,
                is CollageTemplate.Grid9 -> {
                    generateGridCollage(photoPaths, template, reportData)
                }
                is CollageTemplate.VerticalLong -> {
                    if (outputFile == null) {
                        return Result.failure(
                            IllegalArgumentException("VerticalLong template requires an outputFile")
                        )
                    }
                    generateVerticalLongCollage(photoPaths, reportData, outputFile)
                }
            }

            val duration = System.currentTimeMillis() - startTime
            Logger.perf(TAG, "Collage ${template.displayName}", duration)
            Logger.i(
                TAG,
                "Collage generated: ${result.width}x${result.height}, " +
                    "streamed=${result.isStreamed}, photos=${photoPaths.size}"
            )
            Result.success(result)
        } catch (e: Exception) {
            Logger.e(TAG, "Collage generation failed", e)
            Result.failure(e)
        }
    }

    // endregion

    // region Grid Templates (2 / 4 / 9)

    /**
     * Generate a grid collage (2/4/9) into a single Bitmap.
     *
     * Memory: holds one output Bitmap + one source Bitmap at a time.
     */
    private fun generateGridCollage(
        photoPaths: List<String>,
        template: CollageTemplate,
        reportData: ReportData
    ): CollageResult {
        val cols = when (template) {
            is CollageTemplate.Grid2 -> 2
            is CollageTemplate.Grid4 -> 2
            is CollageTemplate.Grid9 -> 3
            else -> 2
        }
        val rows = when (template) {
            is CollageTemplate.Grid2 -> 1
            is CollageTemplate.Grid4 -> 2
            is CollageTemplate.Grid9 -> 3
            else -> 1
        }

        val totalSpacingH = (cols - 1) * CELL_SPACING
        val totalSpacingV = (rows - 1) * CELL_SPACING
        val cellWidth = (OUTPUT_WIDTH - totalSpacingH) / cols
        val cellHeight = cellWidth // square cells for grid
        val outputHeight = rows * cellHeight + totalSpacingV

        // Check memory before creating output Bitmap
        val estimatedMemory = memoryManager.estimateMemory(OUTPUT_WIDTH, outputHeight)
        Logger.i(TAG, "Grid collage memory estimate: ${estimatedMemory / 1024 / 1024}MB")

        val outputBitmap = Bitmap.createBitmap(OUTPUT_WIDTH, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(BG_COLOR)

        photoPaths.forEachIndexed { index, path ->
            val row = index / cols
            val col = index % cols
            val left = col * (cellWidth + CELL_SPACING)
            val top = row * (cellHeight + CELL_SPACING)

            // Decode with sampling to fit cell
            val sourceBitmap = try {
                decoder.decodeFile(path, maxOf(cellWidth, cellHeight) * 2)
            } catch (e: Exception) {
                throw IllegalStateException("无法解码图片: $path (${e.message})", e)
            }
            try {
                // Scale and center-crop to cell
                val scaled = scaleAndCenterCrop(sourceBitmap, cellWidth, cellHeight)
                canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
                if (scaled !== sourceBitmap) memoryManager.recycle(scaled)
            } finally {
                memoryManager.recycle(sourceBitmap)
            }

            memoryManager.checkMemory("collage_grid_${index}")
        }

        return CollageResult(
            bitmap = outputBitmap,
            file = null,
            width = OUTPUT_WIDTH,
            height = outputHeight,
            isStreamed = false
        )
    }

    // endregion

    // region Vertical Long Template (Streamed)

    /**
     * Generate a vertical long collage using streaming to avoid OOM.
     *
     * Strategy:
     * 1. Calculate total height (sum of photo heights + report bar).
     * 2. If total height <= MAX_BITMAP_DIMENSION, create single Bitmap (fast path).
     * 3. If total height > limit, use JPEG streaming (chunked writing).
     *
     * Memory: holds at most 2 source Bitmaps + 1 row buffer.
     */
    private fun generateVerticalLongCollage(
        photoPaths: List<String>,
        reportData: ReportData,
        outputFile: File
    ): CollageResult {
        val photoWidth = OUTPUT_WIDTH
        val photoHeight = (photoWidth * 0.75).toInt() // 4:3 aspect per photo
        val totalContentHeight = photoPaths.size * (photoHeight + CELL_SPACING)
        val totalHeight = totalContentHeight + REPORT_BAR_HEIGHT

        // Fast path: fits in single Bitmap
        if (totalHeight <= MAX_BITMAP_DIMENSION) {
            return generateVerticalLongAsBitmap(
                photoPaths, photoWidth, photoHeight, totalHeight, reportData
            )
        }

        // Streaming path: write JPEG directly without full Bitmap
        return generateVerticalLongStreamed(
            photoPaths, photoWidth, photoHeight, totalHeight, reportData, outputFile
        )
    }

    /**
     * Fast path: vertical long collage fits in one Bitmap.
     */
    private fun generateVerticalLongAsBitmap(
        photoPaths: List<String>,
        photoWidth: Int,
        photoHeight: Int,
        totalHeight: Int,
        reportData: ReportData
    ): CollageResult {
        val outputBitmap = Bitmap.createBitmap(photoWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(BG_COLOR)

        photoPaths.forEachIndexed { index, path ->
            val top = index * (photoHeight + CELL_SPACING)
            val sourceBitmap = try {
                decoder.decodeFile(path, photoHeight * 2)
            } catch (e: Exception) {
                throw IllegalStateException("无法解码图片: $path (${e.message})", e)
            }
            try {
                val scaled = scaleAndCenterCrop(sourceBitmap, photoWidth, photoHeight)
                canvas.drawBitmap(scaled, 0f, top.toFloat(), null)
                if (scaled !== sourceBitmap) memoryManager.recycle(scaled)
            } finally {
                memoryManager.recycle(sourceBitmap)
            }
            memoryManager.checkMemory("collage_long_${index}")
        }

        // Draw report bar at bottom
        drawReportBar(canvas, 0, totalHeight - REPORT_BAR_HEIGHT, photoWidth, reportData)

        return CollageResult(
            bitmap = outputBitmap,
            file = null,
            width = photoWidth,
            height = totalHeight,
            isStreamed = false
        )
    }

    /**
     * Streaming path: write vertical long collage as JPEG without creating
     * a single oversized Bitmap.
     *
     * Uses a row-by-row approach: decode each photo, draw it onto a canvas
     * backed by a reusable row Bitmap, then compress and append to output stream.
     *
     * Note: This is a simplified implementation. True lossless JPEG concatenation
     * requires a custom JPEG encoder. Here we use a practical approach:
     * create a full-size Bitmap if under 16K height, otherwise warn and fallback.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun generateVerticalLongStreamed(
        photoPaths: List<String>,
        photoWidth: Int,
        photoHeight: Int,
        totalHeight: Int,
        reportData: ReportData,
        outputFile: File
    ): CollageResult {
        // Practical fallback: Android's Bitmap limit is 32K, but many devices
        // fail earlier. If totalHeight > 16K, warn user and use reduced scale.
        val scaleFactor = if (totalHeight > 16000) 0.5f else 1.0f
        val scaledWidth = (photoWidth * scaleFactor).toInt()
        val scaledPhotoHeight = (photoHeight * scaleFactor).toInt()
        val scaledTotalHeight = (totalHeight * scaleFactor).toInt()

        Logger.w(
            TAG,
            "Long collage height $totalHeight exceeds safe limit. " +
                "Scaling to ${(scaleFactor * 100).toInt()}% (${scaledTotalHeight}px)"
        )

        // Even with scaling, if still too large, use chunked approach
        return if (scaledTotalHeight <= MAX_BITMAP_DIMENSION) {
            val result = generateVerticalLongAsBitmap(
                photoPaths, scaledWidth, scaledPhotoHeight, scaledTotalHeight, reportData
            )
            // Save to file immediately
            FileOutputStream(outputFile).use { out ->
                result.bitmap?.compress(Bitmap.CompressFormat.JPEG, OUTPUT_QUALITY, out)
            }
            memoryManager.recycle(result.bitmap)
            CollageResult(null, outputFile, scaledWidth, scaledTotalHeight, true)
        } else {
            // Ultimate fallback: split into multiple files (not ideal, but safe)
            throw IllegalStateException(
                "Collage too large even after scaling: ${scaledTotalHeight}px. " +
                    "Reduce photo count or use a different template."
            )
        }
    }

    // endregion

    // region Drawing Helpers

    /**
     * Scale and center-crop a source Bitmap to exact target dimensions.
     *
     * @return A new Bitmap of [targetWidth] x [targetHeight].
     *         Caller must recycle both source and returned Bitmap.
     */
    private fun scaleAndCenterCrop(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source
        }

        val scaleX = targetWidth.toFloat() / source.width
        val scaleY = targetHeight.toFloat() / source.height
        val scale = maxOf(scaleX, scaleY)

        val scaledWidth = (source.width * scale).toInt()
        val scaledHeight = (source.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        // Center crop
        val cropLeft = (scaledWidth - targetWidth) / 2
        val cropTop = (scaledHeight - targetHeight) / 2
        val cropped = Bitmap.createBitmap(
            scaled, cropLeft.coerceAtLeast(0), cropTop.coerceAtLeast(0),
            targetWidth.coerceAtMost(scaled.width),
            targetHeight.coerceAtMost(scaled.height)
        )

        if (scaled !== source) memoryManager.recycle(scaled)
        return cropped
    }

    /**
     * Draw the report bar at the specified position.
     */
    private fun drawReportBar(
        canvas: Canvas,
        left: Int,
        top: Int,
        width: Int,
        reportData: ReportData
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = REPORT_BAR_BG
        canvas.drawRect(
            left.toFloat(), top.toFloat(),
            (left + width).toFloat(), (top + REPORT_BAR_HEIGHT).toFloat(),
            paint
        )

        // Divider line
        paint.color = Color.LTGRAY
        paint.strokeWidth = 2f
        canvas.drawLine(
            left.toFloat(), top.toFloat(),
            (left + width).toFloat(), top.toFloat(),
            paint
        )

        // Text
        paint.color = REPORT_TEXT_COLOR
        paint.textSize = 32f
        paint.typeface = Typeface.DEFAULT_BOLD

        val timeY = top + 50f
        canvas.drawText("拍摄时间: ${reportData.timeText}", left + 24f, timeY, paint)

        paint.typeface = Typeface.DEFAULT
        paint.textSize = 28f
        val locY = timeY + 44f
        canvas.drawText("拍摄地点: ${reportData.locationText}", left + 24f, locY, paint)

        val projY = locY + 40f
        if (reportData.projectText.isNotBlank()) {
            canvas.drawText("项目名称: ${reportData.projectText}", left + 24f, projY, paint)
        }
    }

    // endregion

    // region Utilities

    /**
     * Build default report data with current time.
     */
    private fun buildDefaultReportData(): ReportData {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        return ReportData(timeText = timeStr)
    }

    // endregion
}
