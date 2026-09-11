package com.xayah.core.restic

import android.content.Context
import android.util.Log
import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.ICallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResticRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ResticLogger,
    private val resticNative: ResticNative,
    private val rootService: RemoteRootService,
) {
    companion object {
        private const val TAG = "ResticRepository"
    }

    /**
     * 从 rustic 生成的 .db 直接解析应用备份信息（使用 v_snapshots_full 视图）
     * 替代旧的 parseSqlFileForApps（后者依赖 --sql 文本重建库）
     */
    private fun parseAppsDb(dbFile: File): List<ResticBackupApp> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT  
                id,  
                time,  
                tags_flat,  
                total_bytes_processed  
            FROM v_snapshots_full  
            WHERE tags_flat IS NOT NULL  
        """

            val cursor = db.rawQuery(query, null)
            val apps = mutableListOf<ResticBackupApp>()

            while (cursor.moveToNext()) {
                val snapshotId = cursor.getString(0)
                val snapshotTime = cursor.getString(1)
                val tagsFlat = cursor.getString(2) ?: continue
                val totalBytesProcessed = cursor.getLong(3)

                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }

                tags.forEach { tag ->
                    val parts = tag.split("-")
                    // 标签格式: user_0-com.package.name-1768755852316-apk
                    if (parts.size >= 4 && parts[0].startsWith("user_")) {
                        try {
                            val userId = parts[0].split("_").lastOrNull()?.toIntOrNull() ?: 0
                            val packageName = parts[1]
                            val timestamp = parts[2].toLongOrNull() ?: 0L
                            val dataType = when (parts[3]) {
                                "apk" -> DataType.PACKAGE_APK
                                "user" -> DataType.PACKAGE_USER
                                "user_de" -> DataType.PACKAGE_USER_DE
                                "data" -> DataType.PACKAGE_DATA
                                "obb" -> DataType.PACKAGE_OBB
                                "media" -> DataType.PACKAGE_MEDIA
                                "config" -> DataType.PACKAGE_CONFIG
                                else -> null
                            }

                            if (dataType != null) {
                                apps.add(ResticBackupApp(
                                    packageName = packageName,
                                    userId = userId,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshotId,
                                    snapshotTime = snapshotTime,
                                    tags = tags,
                                    totalBytesProcessed = totalBytesProcessed
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析标签出错: $tag", e)
                        }
                    }
                }
            }
            cursor.close()
            return apps
        } finally {
            db.close()
        }
    }

    // --- 恢复快照（JNI 版）---
    suspend fun restoreSnapshot(
        repoPath: String,
        password: String,
        snapshotId: String,
        targetPath: String,
        snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticProgressCallback? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty())
                    "$snapshotId:$snapshotSubPath" else snapshotId

                // 旧 CLI 的 --glob includePath 是 include 语义；native 侧 Excludes 纯 pattern 是 exclude，
                // 所以要以 "!" 前缀表达 include（与你之前在 repository.rs 里确认的一致）。
                val includeGlob = if (!includePath.isNullOrEmpty()) "!$includePath" else ""

                // 桥接：把 ResticProgressCallback 包成 AIDL 的 ICallback
                val callbackStub = if (progressCallback != null) {
                    object : ICallback.Stub() {
                        // onRestorePlan 规划阶段一次性回传，缓存供 onProgress 拼六字段
                        @Volatile var planFilesTotal = 0L
                        @Volatile var planBytesTotal = 0L
                        @Volatile var planFilesSkipped = 0L
                        @Volatile var planBytesSkipped = 0L

                        override fun onRestorePlan(
                            filesTotal: Long, bytesTotal: Long,
                            filesSkipped: Long, bytesSkipped: Long
                        ) {
                            planFilesTotal = filesTotal
                            planBytesTotal = bytesTotal
                            planFilesSkipped = filesSkipped
                            planBytesSkipped = bytesSkipped
                        }

                        override fun onProgress(
                            readBytes: Long,
                            readTotal: Long,
                            readProgress: Float,
                            writtenBytes: Long,
                            writtenSpeed: Long
                        ) {
                            // 恢复走读取侧：已读回的字节即恢复进度，映射到 bytesWritten 槽
                            progressCallback.onRestoreProgress(
                                0L,               // filesFinished：native 不逐文件回传
                                planFilesTotal,
                                readBytes,        // 已读回字节映射到 bytesWritten 槽
                                if (readTotal > 0) readTotal else planBytesTotal,  // 分母优先用 readTotal，回退 plan 统计
                                planFilesSkipped,
                                planBytesSkipped
                            )
                        }
                    }
                } else null

                rootService.restoreRusticSnapshot(
                    repoPath, password, fullSnapshotId, targetPath,
                    includeGlob = includeGlob,
                    callback = callbackStub
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Rustic restore process exception", e)
                false
            }
        }
    }

    // --- 其他方法 (保持 libsu 优化版) ---

    suspend fun getVersion(): String? {
        return rootService.getRusticVersion()
    }

    /**
     * 重构后的初始化仓库方法
     * 走 AIDL：rootService.initRusticRepository -> RemoteRootServiceImpl -> Rustic.initRepository
     *
     * 压缩配置：从设置页读取压缩级别（datastore key restic_compression_level_int），
     * 经 context.resticCompressionOptions() 转成 options 片段：
     *   -1(AUTO) -> emptyMap()（不设 set_compression → rustic v2 默认压缩）
     *    0(OFF)  -> {COMPRESSION_KEY:"0"}（关闭压缩）
     *  1..22     -> {COMPRESSION_KEY:"<level>"}（指定 zstd 级别）
     * 该 options 全链路透传到 Rust init_repository，仅在建库时写入仓库 config，
     * 已存在仓库不会因改设置而重新压缩。
     *
     * @return Result<String> 成功时包含输出信息，失败时包含异常
     */
    suspend fun initRepository(repoPath: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 从设置页读取压缩级别并转成 options（本地仓库仅承载压缩配置）
                val options = context.resticCompressionOptions()
                Log.i("ResticCompression", "local initRepository options=$options")

                val result = rootService.initRusticRepository(repoPath, password, options)

                if (result.isSuccess) {
                    Result.success("Repository initialized successfully at $repoPath")
                } else {
                    Result.failure(
                        result.exceptionOrNull()
                            ?: Exception("Unknown error during rustic init")
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun listSnapshots(repoPath: String, password: String): List<ResticSnapshot> {
        return withContext(Dispatchers.IO) {
            try {
                val sqlDir = File(context.cacheDir, "sql")
                if (!sqlDir.exists()) sqlDir.mkdirs()

                // 注意：改为 .db（二进制 SQLite），由 librustic 直接写入
                val dbFile = File(sqlDir, "snapshots_local_${System.currentTimeMillis()}.db")

                Log.d(TAG, "执行 JNI listSnapshotsDb，输出路径: ${dbFile.absolutePath}")
                val result = rootService.listRusticSnapshotsDb(repoPath, password, dbFile.absolutePath)
                if (result.isFailure) {
                    Log.e(TAG, "listSnapshotsDb 失败", result.exceptionOrNull())
                    return@withContext emptyList()
                }

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    Log.e(TAG, "DB 文件未生成或为空")
                    return@withContext emptyList()
                }

                val snapshots = parseSnapshotsDb(dbFile)
                dbFile.delete()
                Log.d(TAG, "JNI 模式成功提取 ${snapshots.size} 个快照")
                snapshots
            } catch (e: Exception) {
                Log.e(TAG, "listSnapshots JNI 模式异常", e)
                emptyList()
            }
        }
    }

    /**
     * 从 SQL 文件解析快照信息
     */
    private fun parseSnapshotsDb(dbFile: File): List<ResticSnapshot> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT id, time, hostname, paths_flat, tags_flat  
            FROM v_snapshots_full  
        """
            val cursor = db.rawQuery(query, null)
            val snapshots = mutableListOf<ResticSnapshot>()
            while (cursor.moveToNext()) {
                val pathsFlat = cursor.getString(3) ?: ""
                val tagsFlat = cursor.getString(4) ?: ""
                snapshots.add(
                    ResticSnapshot(
                        id = cursor.getString(0),
                        time = cursor.getString(1),
                        hostname = cursor.getString(2),
                        paths = pathsFlat.split(31.toChar()).filter { it.isNotEmpty() },
                        tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() },
                    )
                )
            }
            cursor.close()
            return snapshots
        } finally {
            db.close()
        }
    }

    suspend fun listBackedUpFiles(repoPath: String, password: String): List<ResticBackupFiles> {
        return withContext(Dispatchers.IO) {
            try {
                // 创建 cache/sql/ 目录（复用既有临时目录约定）
                val sqlDir = File(context.cacheDir, "sql")
                if (!sqlDir.exists()) {
                    sqlDir.mkdirs()
                }

                // 目标 .db 文件（由 librustic 在 root 进程写入）
                val dbFile = File(sqlDir, "snapshots_files_${System.currentTimeMillis()}.db")

                Log.d(TAG, "执行本地文件备份 DB 查询(JNI)")
                // 经 AIDL → RemoteRootServiceImpl → Rustic.listSnapshotsDb → librustic
                rootService.listRusticSnapshotsDb(repoPath, password, dbFile.absolutePath)

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    Log.e(TAG, "DB 生成失败或为空")
                    return@withContext emptyList()
                }

                val files = parseFilesDb(dbFile)
                dbFile.delete()

                Log.d(TAG, "DB 模式成功提取 ${files.size} 个文件备份项")
                files
            } catch (e: Exception) {
                Log.e(TAG, "listBackedUpFiles DB 模式异常", e)
                emptyList()
            }
        }
    }

    /**
     * 从原生 librustic 产出的 .db 文件解析文件备份信息。
     * 直接查询 v_snapshots_full 视图（不再执行 .sql 语句流）。
     */
    private fun parseFilesDb(dbFile: File): List<ResticBackupFiles> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT  
                id,  
                time,  
                paths_flat,  
                tags_flat,  
                total_bytes_processed  
            FROM v_snapshots_full  
            WHERE tags_flat IS NOT NULL  
        """

            val cursor = db.rawQuery(query, null)
            val files = mutableListOf<ResticBackupFiles>()

            while (cursor.moveToNext()) {
                val snapshotId = cursor.getString(0)
                val snapshotTime = cursor.getString(1)
                val pathsFlat = cursor.getString(2) ?: ""
                val tagsFlat = cursor.getString(3) ?: continue
                val totalBytesProcessed = cursor.getLong(4)

                // paths_flat / tags_flat 使用 char(31) 作为分隔符
                val paths = pathsFlat.split(31.toChar()).filter { it.isNotEmpty() }
                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }

                tags.forEach { tag ->
                    val parts = tag.split("-")
                    // 标签格式: mediaName-timestamp-filesbackup/filesconfig
                    if (parts.size >= 3) {
                        try {
                            val mediaName = parts.dropLast(2).joinToString("-")
                            val timestamp = parts[parts.size - 2].toLongOrNull() ?: 0L
                            val dataType = when (parts.last()) {
                                "filesbackup" -> DataType.PACKAGE_MEDIA
                                "filesconfig" -> DataType.PACKAGE_CONFIG
                                else -> null
                            }

                            if (dataType != null) {
                                val fullPath = paths.firstOrNull() ?: ""
                                files.add(ResticBackupFiles(
                                    mediaName = mediaName,
                                    fullPath = fullPath,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshotId,
                                    snapshotTime = snapshotTime,
                                    tags = tags,
                                    totalBytesProcessed = totalBytesProcessed
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析文件标签出错: $tag", e)
                        }
                    }
                }
            }
            cursor.close()
            return files
        } finally {
            db.close()
        }
    }

    suspend fun forgetSnapshot(
        repoPath: String,
        password: String,
        snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = rootService.forgetRusticSnapshot(repoPath, password, snapshotId)
            Log.d(TAG, "Forget snapshot 结果: ${result.isSuccess}")
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "删除快照失败: ${e.message}", e)
            false
        }
    }

    suspend fun pruneRepository(
        repoPath: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 本地仓库沿用 --max-unused 10%，本地单进程独占访问，安全启用即时删除
            val result = rootService.pruneRusticRepository(repoPath, password, "10%", instantDelete = true)
            Log.d(TAG, "Prune 结果: ${result.isSuccess}")
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Prune 失败: ${e.message}", e)
            false
        }
    }

    suspend fun validateRepository(repoPath: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            rootService.validateRusticRepository(repoPath, password).isSuccess
        }

    suspend fun deleteRepository(repoPath: String): Boolean = withContext(Dispatchers.IO) {
        try { File(repoPath).deleteRecursively() } catch (e: Exception) { false }
    }

    suspend fun checkRepository(repoPath: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            // 迁移说明：改走 JNI（librustic.so，经 RootService），不再 spawn restic 二进制。
            // 原生 checkRepository 成功返回、失败抛异常（含"仓库不存在/config 缺失"），
            // 因此用 Result.isSuccess 直接映射为 Boolean，替代原来对 stdout 文本的解析。
            val result = rootService.checkRusticRepository(repoPath, password)
            if (result.isSuccess) {
                true
            } else {
                Log.w(TAG, "Rustic check failed: ${result.exceptionOrNull()?.message}")
                false
            }
        }
    }

    suspend fun listBackedUpApps(repoPath: String, password: String): List<ResticBackupApp> {
        return withContext(Dispatchers.IO) {
            try {
                // 创建 cache/sql/ 目录（沿用旧目录，仅扩展名改为 .db）
                val sqlDir = File(context.cacheDir, "sql")
                if (!sqlDir.exists()) {
                    sqlDir.mkdirs()
                }

                val dbFile = File(sqlDir, "snapshots_apps_${System.currentTimeMillis()}.db")

                Log.d(TAG, "执行本地应用备份 DB 查询（JNI）: ${dbFile.absolutePath}")

                // 经 AIDL → RemoteRootServiceImpl → Rustic.listSnapshotsDb 在 root 进程生成 .db
                val result = rootService.listRusticSnapshotsDb(repoPath, password, dbFile.absolutePath)

                if (result.isFailure) {
                    Log.e(TAG, "listBackedUpApps DB 生成失败", result.exceptionOrNull())
                    dbFile.delete()
                    return@withContext emptyList()
                }

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    Log.e(TAG, "DB 文件未生成或为空")
                    dbFile.delete()
                    return@withContext emptyList()
                }

                val apps = parseAppsDb(dbFile)
                dbFile.delete()

                Log.d(TAG, "DB 模式成功提取 ${apps.size} 个应用备份项")
                apps
            } catch (e: Exception) {
                Log.e(TAG, "listBackedUpApps DB 模式异常", e)
                emptyList()
            }
        }
    }

    /**
     * 使用 Rustic 备份到本地仓库（JNI 版）
     * 经 RootService → RemoteRootServiceImpl.createRusticSnapshot → Rustic.createSnapshot → librustic.so
     *
     * 返回值契约保持不变：Pair<退出码, 输出字符串>
     * - 成功：Pair(0, 合成的 summary JSON)，其中含 "message_type":"summary" 与 "snapshot_id":"<id>"，
     *   供上层 AbstractBackupService.extractSnapshotIdFromJson 正则抠出快照 ID。
     * - 失败：Pair(1, 错误信息)
     *
     * 注意：JNI 路径不再走 RUSTIC_STOP_FILE / additionalEnv / usePty；
     * 备份中途取消由调用方传入 cancelId，取消时从另一协程调 rootService.cancelRusticBackup(cancelId)。
     * additionalEnv 仅保留以兼容签名。
     */
    suspend fun backupWithResticToLocal(
        repoPath: String,
        password: String,
        filePath: String,
        tags: List<String>,
        additionalEnv: Map<String, String> = emptyMap(),
        progressCallback: ResticProgressCallback? = null,
        cancelId: Long = 0L,
    ): Pair<Int, String> {
        return withContext(Dispatchers.IO) {
            try {
                // ICallback 适配：JNI 三参进度 → ResticProgressCallback.onBackupProgress 五参
                val callback: ICallback? = if (progressCallback != null) {
                    object : ICallback.Stub() {
                        override fun onProgress(
                            readBytes: Long,
                            readTotal: Long,
                            readProgress: Float,
                            writtenBytes: Long,
                            writtenSpeed: Long
                        ) {
                            progressCallback.onBackupProgress(
                                percentDone = readProgress,   // 读取百分比 → 第一行进度
                                bytesDone = readBytes,        // ← 路线B：本地无写出计数，改用已处理源字节作为累积量
                                bytesTotal = readTotal,       // 源总大小 → 第一行分母
                                filesDone = readBytes,        // 已读原始字节 → 第一行分子
                                filesTotal = 0L,              // 本地路径无文件数
                                speed = 0L                    // ← 路线B：本地无网速，显式置 0，上层不拼接速度文本
                            )
                        }

                        override fun onRestorePlan(
                            filesTotal: Long,
                            bytesTotal: Long,
                            filesSkipped: Long,
                            bytesSkipped: Long
                        ) {
                            // 备份链路不需要 restore plan 统计，空实现
                        }
                    }
                } else null

                // createRusticSnapshot 调用前：记录进入与本次备份的 cancelId
                Log.i("RusticCancel", "backupWithResticToLocal enter, cancelId=$cancelId")

                val snapshotId = rootService.createRusticSnapshot(
                    repositoryPath = repoPath,
                    password = password,
                    sourcePaths = listOf(filePath),
                    tags = tags,
                    callback = callback,
                    cancelId = cancelId,
                )

                if (snapshotId.isNotBlank()) {
                    // 合成一行 summary JSON，令上层 extractSnapshotIdFromJson 正则命中
                    val summaryJson = """{"message_type":"summary","snapshot_id":"$snapshotId"}"""
                    Pair(0, summaryJson)
                } else {
                    Pair(1, "Rustic returned an empty snapshot ID")
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                if (msg.contains("cancel", ignoreCase = true)) {
                    Log.i("RusticCancel", "backupWithResticToLocal cancelled by user, cancelId=$cancelId, msg=$msg")
                    Pair(1, "用户取消")
                } else {
                    Log.e("RusticCancel", "backupWithResticToLocal failed, cancelId=$cancelId, msg=$msg")
                    Pair(1, msg)
                }
            }
        }
    }

    interface ResticProgressCallback {
        fun onRestoreProgress(filesFinished: Long, filesTotal: Long, bytesWritten: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long)
        // 备份进度（新增 speed: Long，单位 bytes/s，JNI 原生已提供）
        fun onBackupProgress(percentDone: Float, bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long, speed: Long = 0L)
    }
}

@Serializable
data class ResticSnapshot(
    val id: String,
    val time: String,
    val hostname: String,
    val paths: List<String>,
    val tags: List<String>,
    val summary: SnapshotSummary? = null
)

@Serializable
data class SnapshotSummary(
    val total_bytes_processed: Long? = null,
    val files_new: Long? = null,
    val files_changed: Long? = null,
    val files_unmodified: Long? = null,
    val total_files_processed: Long? = null,
    val dirs_new: Long? = null,
    val dirs_changed: Long? = null,
    val dirs_unmodified: Long? = null,
    val data_blobs: Long? = null,
    val tree_blobs: Long? = null,
    val data_added: Long? = null,
    val data_added_packed: Long? = null
)