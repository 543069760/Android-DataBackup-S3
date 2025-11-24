package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import com.xayah.core.model.database.UploadIdEntity

@Dao
interface UploadIdDao {
    @Insert
    suspend fun insert(entity: UploadIdEntity): Long

    @Query("DELETE FROM UploadIdEntity WHERE uploadId = :uploadId")
    suspend fun deleteByUploadId(uploadId: String)

    @Query("SELECT * FROM UploadIdEntity")
    suspend fun getAll(): List<UploadIdEntity>

    @Query("DELETE FROM UploadIdEntity WHERE id = :id")
    suspend fun deleteById(id: Long)
}