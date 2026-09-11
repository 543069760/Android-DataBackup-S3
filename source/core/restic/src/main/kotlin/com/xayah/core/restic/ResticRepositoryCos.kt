package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.restic.ResticBackupFiles   // listBackedUpFilesFromS3WithSqlJni 返回类型
import com.xayah.core.rootservice.ICallback            // ICallback? / ICallback.Stub()
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * COS/S3 备份的 JNI 实现（纯 JNI，不依赖 restic 二进制）。
 * 共享 helper（formatOpenDALRoot / buildOpenDALEndpoint / parseAppsDb）统一由 ResticShared 提供。
 *
 * options key 依据 opendal 0.57.0 `opendal-service-cos` 的 CosConfig 字段确定：
 *   root / endpoint / secret_id / secret_key / bucket（无 region，凭据用 secret_id/secret_key）。
 */
@Singleton
class ResticRepositoryCos @Inject constructor(
    private val shared: ResticShared,
) {
    // initCosRepository —— 对应 initS3Repository
    // 压缩配置：仅在建库时合并压缩 key；语义与本地一致
    //   -1(AUTO) -> resticCompressionOptions() 返回 emptyMap()（不设 set_compression → rustic v2 默认压缩）
    //    0(OFF)  -> {COMPRESSION_KEY:"0"}（关闭压缩）
    //  1..22     -> {COMPRESSION_KEY:"<level>"}（指定 zstd 级别）
    // 该 key 会被 Rust 侧 backends() 过滤，不会进入 opendal 后端配置。
    suspend fun initCosRepository(
        extra: S3Extra, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 仅在 init 这一处合并压缩 key；buildS3BackendOptions 本身不改（被 backup/restore/prune/list 共用）
            val options = buildS3BackendOptions(extra, remotePath) + shared.context.resticCompressionOptions()
            Log.i("ResticCompression", "cos initCosRepository options=$options")
            val result = shared.rootService.initRusticRepository("opendal:cos", password, options)
            if (result.isSuccess) Result.success("COS repository initialized")
            else Result.failure(Exception(result.exceptionOrNull()?.message ?: "Unknown error during rustic init"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // backupFileToCos —— 对应 backupFileToS3，返回 Pair<Int,String>（保持上层契约）
    suspend fun backupFileToCos(
        extra: S3Extra, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val options = buildS3BackendOptions(extra, remotePath)

            // RusticCancel: 进入日志，确认这一层收到的 cancelId（若显示 0 说明上层没透传）
            Log.i("RusticCancel", "backupFileToCos enter, cancelId=$cancelId")

            val callback: ICallback? = progressCallback?.let { cb ->
                object : ICallback.Stub() {
                    // 5 参签名，与 ICallback.aidl（commit cbd81d0e）一致
                    override fun onProgress(
                        readBytes: Long, readTotal: Long, readProgress: Float,
                        writtenBytes: Long, writtenSpeed: Long
                    ) {
                        cb.onBackupProgress(
                            percentDone = readProgress,   // 读取百分比 → 第一行进度
                            bytesDone   = writtenBytes,   // 真实写出字节 → 第二行写出量
                            bytesTotal  = readTotal,      // 源总大小 → 第一行分母
                            filesDone   = readBytes,      // 已读原始字节 → 第一行分子
                            filesTotal  = 0L,             // COS 路径无文件数
                            speed       = writtenSpeed    // 写出速度 → 第二行速度
                        )
                    }

                    override fun onRestorePlan(
                        filesTotal: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {}
                }
            }

            val snapshotId = shared.rootService.createRusticSnapshot(
                repositoryPath = "opendal:cos",
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = options,
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFileToCos 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToCos 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToCos cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToCos failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        }
    }

    // 列出全部快照（含 tags），用于筛选 __icons__ 图标快照
    suspend fun listSnapshotsFromCos(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_icons_s3_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:cos", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val snapshots = shared.parseSnapshotsDb(dbFile); dbFile.delete(); snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshotsFromCos 异常", e); emptyList()
        }
    }

    // restoreSnapshotFromCos —— 对应 restoreSnapshotFromS3
    suspend fun restoreSnapshotFromCos(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) "$snapshotId:$snapshotSubPath" else snapshotId
            // 与本地 restoreSnapshot 一致：include 语义要加 "!" 前缀
            val includeGlob = if (!includePath.isNullOrEmpty()) "!$includePath" else ""
            val callback: ICallback? = if (progressCallback != null) object : ICallback.Stub() {
                @Volatile var planFilesTotal = 0L; @Volatile var planBytesTotal = 0L
                @Volatile var planFilesSkipped = 0L; @Volatile var planBytesSkipped = 0L
                override fun onRestorePlan(filesTotal: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long) {
                    planFilesTotal = filesTotal; planBytesTotal = bytesTotal
                    planFilesSkipped = filesSkipped; planBytesSkipped = bytesSkipped
                }
                override fun onProgress(
                    readBytes: Long, readTotal: Long, readProgress: Float,
                    writtenBytes: Long, writtenSpeed: Long
                ) {
                    progressCallback.onRestoreProgress(
                        0L, planFilesTotal, readBytes,
                        if (readTotal > 0) readTotal else planBytesTotal,
                        planFilesSkipped, planBytesSkipped
                    )
                }
            } else null
            val result = shared.rootService.restoreRusticSnapshot(
                repositoryPath = "opendal:cos", password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = options, includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) { false }
    }

    // forgetSnapshotFromCos —— 对应 forgetSnapshotFromS3
    suspend fun forgetSnapshotFromCos(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            shared.rootService.forgetRusticSnapshot("opendal:cos", password, snapshotId, options).isSuccess
        } catch (e: Exception) { false }
    }

    // pruneCosRepository —— 对应 pruneS3Repository（--max-unused unlimited）
    suspend fun pruneCosRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            shared.rootService.pruneRusticRepository("opendal:cos", password, "unlimited", options, instantDelete = true).isSuccess
        } catch (e: Exception) { false }
    }

    // listBackedUpFilesFromS3WithSqlJni —— 对应 listBackedUpFilesFromS3WithSql，与 apps 版同构
    suspend fun listBackedUpFilesFromS3WithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_s3_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:cos", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 【JNI 版】从 S3/COS 获取应用备份列表。
     * 与 ResticRepository.listBackedUpAppsFromS3WithSql（二进制路径，已随二进制移除）功能等价。
     */
    /** 只读缓存：不触发网络/JNI，供进列表时秒开渲染 */
    suspend fun readCachedApps(cloudEntity: CloudEntity): List<ResticBackupApp> =
        withContext(Dispatchers.IO) { shared.readCachedApps(cloudEntity.name) }

    /** 重建：走 JNI 拉取并原子写入持久 db，供后台静默刷新 */
    suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            shared.refreshAppsDb(cloudEntity.name, "opendal:cos", password, options)
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (COS) 异常", e)
            shared.readCachedApps(cloudEntity.name)
        }
    }

    /** 兼容旧调用点：语义为重建 */
    suspend fun listBackedUpAppsFromS3WithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = refreshAndListApps(cloudEntity, password)

    /**
     * 把 S3Extra 翻译成 opendal:cos 后端所需的 options map。
     * key 名对应 opendal 0.57.0 CosConfig 字段，原样透传给 opendal Operator::via_iter。
     * 密码不放 map，单独作为 password 参数传给 JNI。
     */
    private fun buildS3BackendOptions(extra: S3Extra, remotePath: String): Map<String, String> {
        return mapOf(
            "bucket" to extra.bucket,
            "root" to shared.formatOpenDALRoot(remotePath),
            "endpoint" to shared.buildOpenDALEndpoint(extra),
            "secret_id" to extra.accessKeyId,
            "secret_key" to extra.secretAccessKey,
        )
    }
}