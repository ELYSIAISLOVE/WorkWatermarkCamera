package com.watermark.camera.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a photo index record.
 *
 * This is NOT the photo itself (stored in MediaStore/DCIM),
 * but a local index for quick lookup in the collage feature.
 */
@Entity(tableName = "photos")
data class PhotoEntity(

    /**
     * Auto-generated primary key.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * URI of the photo in MediaStore.
     */
    val uri: String,

    /**
     * File path for direct access (if available).
     */
    val filePath: String?,

    /**
     * File name (e.g., WM_20260821_075030.jpg).
     */
    val fileName: String,

    /**
     * Timestamp when the photo was taken (milliseconds).
     */
    val timestamp: Long,

    /**
     * Photo width in pixels.
     */
    val width: Int,

    /**
     * Photo height in pixels.
     */
    val height: Int,

    /**
     * Whether the photo has a watermark.
     */
    val hasWatermark: Boolean = true,

    /**
     * Whether the photo has EXIF verification data.
     */
    val hasExifVerification: Boolean = true,

    /**
     * Latitude from GPS (null if unavailable).
     */
    val latitude: Double? = null,

    /**
     * Longitude from GPS (null if unavailable).
     */
    val longitude: Double? = null,

    /**
     * Custom watermark fields stored as JSON string.
     */
    val watermarkConfigJson: String? = null
)
