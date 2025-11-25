package com.xayah.core.model.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xayah.core.model.CompressionType
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import kotlinx.serialization.Serializable

@Serializable
data class MediaIndexInfo(
    var opType: OpType,
    var name: String,
    var compressionType: CompressionType,
    var preserveId: Long,
    var cloud: String,
    var backupDir: String,
    var backupTimestamp: Long = 0L,  // 新增字段
)

@Serializable
data class MediaInfo(
    var path: String,
    var dataBytes: Long,
    var displayBytes: Long,
)

@Serializable
data class MediaExtraInfo(
    @ColumnInfo(defaultValue = "0") var lastBackupTime: Long,
    var blocked: Boolean,
    var activated: Boolean,
    var existed: Boolean,
    @ColumnInfo(defaultValue = "0") var isProtected: Boolean = false,  // 新增字段
    @ColumnInfo(defaultValue = "0") var isCanceled: Boolean = false,
)

@Serializable
@Entity
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) var id: Long,
    @Embedded(prefix = "indexInfo_") var indexInfo: MediaIndexInfo,
    @Embedded(prefix = "mediaInfo_") var mediaInfo: MediaInfo,
    @Embedded(prefix = "extraInfo_") var extraInfo: MediaExtraInfo,
) {
    private val ctName: String
        get() = indexInfo.compressionType.type

    val name: String
        get() = indexInfo.name

    val path: String
        get() = mediaInfo.path

    val preserveId: Long
        get() = indexInfo.preserveId

    val displayStatsBytes: Double
        get() = mediaInfo.displayBytes.toDouble()

    val archivesRelativeDir: String
        get() {
            return if (indexInfo.backupTimestamp > 0L) {
                "${indexInfo.name}@${indexInfo.backupTimestamp}"
            } else {
                // 向后兼容旧格式
                if (preserveId != 0L) "${indexInfo.name}@${preserveId}" else indexInfo.name
            }
        }

    val existed: Boolean
        get() = extraInfo.existed

    val enabled: Boolean
        get() = extraInfo.existed && path.isNotEmpty()
}

fun MediaEntity.asExternalModel() = File(
    id = id,
    name = name,
    path = path,
    preserveId = preserveId,
    selected = extraInfo.activated,
    backupTimestamp = indexInfo.backupTimestamp,  // 新增
    isProtected = extraInfo.isProtected  // 新增
)