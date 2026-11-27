package com.watermark.camera.data.collage

import androidx.annotation.IntRange

/**
 * Collage layout templates supported by the engine.
 *
 * Templates:
 * - [GRID_2]: 2 photos, horizontal split (1x2)
 * - [GRID_4]: 4 photos, 2x2 grid
 * - [GRID_9]: 9 photos, 3x3 grid
 * - [VERTICAL_LONG]: Vertical scroll strip (2-10 photos), bottom report bar
 *
 * @property maxPhotos Maximum number of photos this template supports.
 * @property displayName Human-readable name for UI.
 */
sealed class CollageTemplate(
    @IntRange(from = 2, to = 10) val maxPhotos: Int,
    val displayName: String
) {

    /**
     * 2-photo horizontal layout.
     * ┌─────┬─────┐
     * │  1  │  2  │
     * └─────┴─────┘
     */
    data object Grid2 : CollageTemplate(2, "双图并排")

    /**
     * 4-photo 2x2 grid layout.
     * ┌─────┬─────┐
     * │  1  │  2  │
     * ├─────┼─────┤
     * │  3  │  4  │
     * └─────┴─────┘
     */
    data object Grid4 : CollageTemplate(4, "四宫格")

    /**
     * 9-photo 3x3 grid layout.
     * ┌─────┬─────┬─────┐
     * │  1  │  2  │  3  │
     * ├─────┼─────┼─────┤
     * │  4  │  5  │  6  │
     * ├─────┼─────┼─────┤
     * │  7  │  8  │  9  │
     * └─────┴─────┴─────┘
     */
    data object Grid9 : CollageTemplate(9, "九宫格")

    /**
     * Vertical long strip layout.
     * Photos are stacked vertically with a report bar at the bottom.
     * Supports 2-10 photos.
     * ┌─────┐
     * │  1  │
     * ├─────┤
     * │  2  │
     * ├─────┤
     * │ ... │
     * ├─────┤
     * │报告栏│
     * └─────┘
     */
    data object VerticalLong : CollageTemplate(10, "竖向长图")

    companion object {
        /**
         * All available templates for UI display.
         */
        val ALL = listOf(Grid2, Grid4, Grid9, VerticalLong)

        /**
         * Get template by ordinal index.
         */
        fun fromIndex(index: Int): CollageTemplate = ALL.getOrElse(index) { Grid2 }
    }
}
