package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.ICallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 备份的 JNI 实现（纯 JNI，不依赖 restic 二进制），参照 ResticRepositoryFtp。
 * scheme 使用 opendal:webdav，options key 依据 opendal 0.57.0 opendal-service-webdav 的 WebdavConfig 字段：
 *   endpoint（完整 URL，含 http(s)://）/ root / username / password。
 * 说明：username/password（WebDAV 登录）放入 options map；restic 仓库密码单独作为 password 参数传 JNI。
 * 注意：opendal webdav 不支持跳过 TLS 校验，故不传任何 insecure/证书相关 key。
 */
@Singleton
class ResticRepositoryWebdav @Inject constructor(
    private val shared: ResticShared,
) {
    // initWebdavRepository —— 对应 initFtpRepository
    suspend fun initWebdavRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, remotePath)
            Log.i("ResticWebdavRoot", "initWebdav options=$options root=${options["root"]} endpoint=${options["endpoint"]}")

            // 1) 建库
            val initResult = shared.rootService.initRusticRepository("opendal:webdav", password, options)
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    Exception(initResult.exceptionOrNull()?.message ?: "Unknown error during rustic init")
                )
            }

            // 2) 复核：用相同 options 确认仓库 config 真的写进了 WebDAV
            val exists = shared.rootService.rusticRepositoryExists("opendal:webdav", options)
            if (!exists) {
                Log.e(ResticShared.TAG, "WebDAV init 报成功但 repositoryExists=false")
                return@withContext Result.failure(
                    Exception("init 报成功但仓库 config 未写入 WebDAV")
                )
            }

            Log.d(ResticShared.TAG, "WebDAV repository initialized and verified")
            Result.success("WebDAV repository initialized")
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "initWebdavRepository 异常", e)
            Result.failure(e)
        }
    }

    // backupFileToWebdav —— 对应 backupFileToFtp，返回 Pair<Int,String>
    suspend fun backupFileToWebdav(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, remotePath)

            Log.i("ResticWebdavRoot", "backupWebdav remotePath=$remotePath root=${options["root"]}")

            val callback: ICallback? = progressCallback?.let { cb ->
                object : ICallback.Stub() {
                    override fun onProgress(
                        readBytes: Long, readTotal: Long, readProgress: Float,
                        writtenBytes: Long, writtenSpeed: Long
                    ) {
                        cb.onBackupProgress(
                            percentDone = readProgress,
                            bytesDone   = writtenBytes,
                            bytesTotal  = readTotal,
                            filesDone   = readBytes,
                            filesTotal  = 0L,
                            speed       = writtenSpeed
                        )
                    }

                    override fun onRestorePlan(
                        filesTotal: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {}
                }
            }

            val snapshotId = shared.rootService.createRusticSnapshot(
                repositoryPath = "opendal:webdav",
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = options,
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFileToWebdav 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToWebdav 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToWebdav cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToWebdav failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        }
    }

    // restoreSnapshotFromWebdav —— 对应 restoreSnapshotFromFtp
    suspend fun restoreSnapshotFromWebdav(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, cloudEntity.remote)
            Log.i("ResticWebdavRoot", "restoreWebdav root=${options["root"]}")
            val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) "$snapshotId:$snapshotSubPath" else snapshotId
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
                repositoryPath = "opendal:webdav", password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = options, includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) { false }
    }

    // forgetSnapshotFromWebdav —— 对应 forgetSnapshotFromFtp
    suspend fun forgetSnapshotFromWebdav(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, cloudEntity.remote)
            shared.rootService.forgetRusticSnapshot("opendal:webdav", password, snapshotId, options).isSuccess
        } catch (e: Exception) { false }
    }

    // pruneWebdavRepository —— 对应 pruneFtpRepository（--max-unused unlimited）
    suspend fun pruneWebdavRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, cloudEntity.remote)
            shared.rootService.pruneRusticRepository("opendal:webdav", password, "unlimited", options, instantDelete = true).isSuccess
        } catch (e: Exception) { false }
    }

    // listBackedUpFilesFromWebdavWithSqlJni —— 对应 listBackedUpFilesFromFtpWithSqlJni
    suspend fun listBackedUpFilesFromWebdavWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_webdav_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:webdav", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() }
    }

    fun readCachedApps(cloudEntity: CloudEntity): List<ResticBackupApp> =
        shared.readCachedApps(cloudEntity.name)

    suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        try {
            val options = buildWebdavBackendOptions(cloudEntity, cloudEntity.remote)
            Log.d(ResticShared.TAG, "refreshAndListApps (WebDAV) root=${options["root"]}")
            shared.refreshAppsDb(
                accountId = cloudEntity.name,
                repoPath = "opendal:webdav",
                password = password,
                options = options,
            )
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (WebDAV) 异常", e)
            emptyList()
        }
    }

    suspend fun listBackedUpAppsFromWebdavWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = refreshAndListApps(cloudEntity, password)

    // listSnapshotsFromWebdav —— 对应 listBackedUpAppsFromWebdavWithSqlJni，但用 parseSnapshotsDb（不按四段式过滤，保留 __icons__ 快照）
    suspend fun listSnapshotsFromWebdav(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        try {
            val repoPath = "opendal:webdav"
            val options = buildWebdavBackendOptions(cloudEntity, cloudEntity.remote)

            val sqlDir = File(shared.context.cacheDir, "sql")
            if (!sqlDir.exists()) sqlDir.mkdirs()

            val dbFile = File(sqlDir, "snapshots_webdav_${System.currentTimeMillis()}.db")

            Log.d(ResticShared.TAG, "执行 JNI listSnapshotsDb (WebDAV/snapshots)，repo=$repoPath，输出=${dbFile.absolutePath}")

            val result = shared.rootService.listRusticSnapshotsDb(
                repositoryPath = repoPath,
                password = password,
                dbPath = dbFile.absolutePath,
                options = options,
            )
            if (result.isFailure) {
                Log.e(ResticShared.TAG, "listRusticSnapshotsDb (WebDAV/snapshots) 失败", result.exceptionOrNull())
                return@withContext emptyList()
            }
            if (!dbFile.exists() || dbFile.length() == 0L) {
                Log.e(ResticShared.TAG, "DB 文件未生成或为空 (WebDAV/snapshots)")
                return@withContext emptyList()
            }

            val snapshots = shared.parseSnapshotsDb(dbFile)
            dbFile.delete()
            Log.d(ResticShared.TAG, "JNI 模式 (WebDAV) 成功提取 ${snapshots.size} 个快照")
            snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshotsFromWebdav 异常", e)
            emptyList()
        }
    }

    /**
     * 把 CloudEntity(host/user/pass) 翻译成 opendal:webdav 后端 options map。
     * key 名对应 opendal 0.57.0 WebdavConfig 字段，原样透传给 opendal Operator::via_iter。
     * restic 仓库密码不放 map，单独作为 password 参数传给 JNI。
     * 不传任何 insecure/证书跳过 key（opendal webdav 不支持）。
     */
    private fun buildWebdavBackendOptions(cloudEntity: CloudEntity, remotePath: String): Map<String, String> {
        // 解析 extra 以校验 JSON 合法（webdav 的 endpoint/username/password 均来自 CloudEntity，本身不需要 extra 字段）
        val extra = ResticShared.json.decodeFromString<WebDAVExtra>(cloudEntity.extra)
        val options = mapOf(
            "endpoint" to shared.buildOpenDALWebdavEndpoint(cloudEntity.host),
            "root" to shared.formatOpenDALRoot(remotePath),
            "username" to cloudEntity.user,
            "password" to cloudEntity.pass,
        )
        Log.i("ResticWebdavRoot", "buildWebdavOptions remotePath=$remotePath root=${options["root"]} endpoint=${options["endpoint"]} protocol=${extra.protocol}")
        return options
    }
}