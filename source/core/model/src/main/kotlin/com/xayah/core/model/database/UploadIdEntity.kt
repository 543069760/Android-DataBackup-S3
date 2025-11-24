package com.xayah.core.model.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class UploadIdEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uploadId: String,
    val bucket: String,
    val key: String,
    val timestamp: Long,
    val cloudName: String
)