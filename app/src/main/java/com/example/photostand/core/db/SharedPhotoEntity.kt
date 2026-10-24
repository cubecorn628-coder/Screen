// File: app/src/main/java/com/example/photostand/core/db/SharedPhotoEntity.kt
package com.example.photostand.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing device-local metadata about shared photos,
 * fulfilling the local-first storage philosophy of PhotoStand.
 */
@Entity(tableName = "shared_photos")
data class SharedPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val senderDeviceId: String,
    val senderDeviceName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val fileSizeBytes: Long = 0,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val isFavorite: Boolean = false,
    val notes: String? = null
)
