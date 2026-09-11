package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.ICallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FTP 备份的实现，彻底切换到 librclone serve restic（结构对照 ResticRepositorySftp）。
 *
 * 与旧 opendal:ftp 版的三点区别：
 *  1) repositoryPath 使用 rclone serve restic 起服务后返回的 rest: URL（session.restUrl），
 *     而不是 opendal:ftp；opendal 在 NAT/被动模式/TLS FTP 上会卡 0 字节，rclone 可穿透。
 *  2) options 一律传 emptyMap()——FTP 的 host/port/user/pass 认证由 rclone 在 serve 侧完成，
 *     rustic 侧只当作普通 REST 后端。
 *  3) 每个方法都用 serve 生命周期包住：start() 起服务拿端口，finally { stop(id) } 收尾。
 *     serve 必须在整批 JNI 调用期间一直存活，端口为 localhost:0 动态分配、不可跨会话复用。
 *
 * 说明：restic 仓库密码单独作为 password 参数传 JNI（与 SFTP 一致）。
 */
@Singleton
class ResticRepositoryFtp @Inject constructor(
    private val shared: ResticShared,
    private val rcloneServe: RcloneServe,
) {
    // initFtpRepository —— 对应 initSftpRepository
    suspend fun initFtpRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)
        try {
            Log.i("ResticFtpRoot", "initFtp restUrl=${session.restUrl} remotePath=$remotePath")

            // 1) 建库
            val initResult = shared.rootService.initRusticRepository(session.restUrl, password, emptyMap())
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    Exception(initResult.exceptionOrNull()?.message ?: "Unknown error during rustic init")
                )
            }

            // 2) 复核：确认仓库 config 真的写进了 FTP
            val exists = shared.rootService.rusticRepositoryExists(session.restUrl, emptyMap())
            if (!exists) {
                Log.e(ResticShared.TAG, "FTP init 报成功但 repositoryExists=false")
                return@withContext Result.failure(
                    Exception("init 报成功但仓库 config 未写入 FTP（疑似 rclone serve/认证失败）")
                )
            }

            Log.d(ResticShared.TAG, "FTP repository initialized and verified")
            Result.success("FTP repository initialized")
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "initFtpRepository 异常", e)
            Result.failure(e)
        } finally {
            stopServe(session)
        }
    }

    // backupFileToFtp —— 对应 backupFileToSftp，返回 Pair<Int,String>（保持上层契约）
    suspend fun backupFileToFtp(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)

        // 轮询控制标志与句柄（在 finally 里停止 + join，避免泄漏）
        val polling = AtomicBoolean(true)
        var pollJob: Job? = null

        try {
            Log.i("ResticFtpRoot", "backupFtp restUrl=${session.restUrl} remotePath=$remotePath")

            // 关键：每次备份各起一个 serve，但 rclone accounting 是进程级累计的，
            // 必须先 reset 出干净基准，否则 core/stats 会带上历史字节。
            rcloneServe.resetStats()

            // 起轮询协程：从 rclone core/stats 读实时累计字节 + 速度，驱动进度显示。
            if (progressCallback != null) {
                pollJob = CoroutineScope(coroutineContext).launch {
                    while (polling.get() && isActive) {
                        val (bytes, speed) = rcloneServe.readStatsBytesAndSpeed()
                        Log.d("FtpStatsPoll", "poll bytes=$bytes speed=$speed")
                        progressCallback.onBackupProgress(
                            percentDone = 0f,
                            bytesDone   = bytes,
                            bytesTotal  = 0L,
                            filesDone   = 0L,
                            filesTotal  = 0L,
                            speed       = speed
                        )
                        delay(300L)
                    }
                }
            }

            // ICallback 仍需传给 createRusticSnapshot 维持回调链路，但 onProgress 不再驱动
            // 进度显示（避免与轮询两路互相覆盖）。取消走 cancelId，与本回调无关。
            val callback: ICallback? = progressCallback?.let {
                object : ICallback.Stub() {
                    override fun onProgress(
                        readBytes: Long, readTotal: Long, readProgress: Float,
                        writtenBytes: Long, writtenSpeed: Long
                    ) {
                        // no-op：进度显示改由 core/stats 轮询驱动
                    }

                    override fun onRestorePlan(
                        filesTotal: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {}
                }
            }

            val snapshotId = shared.rootService.createRusticSnapshot(
                repositoryPath = session.restUrl,
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = emptyMap(),
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFileToFtp 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToFtp 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToFtp cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToFtp failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        } finally {
            // 先停轮询、等它干净结束，再停 serve，避免 serve 停后轮询仍打 RPC。
            polling.set(false)
            runCatching { pollJob?.cancelAndJoin() }
            stopServe(session)
        }
    }

    suspend fun listSnapshotsFromFtp(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_icons_ftp_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb(session.restUrl, password, dbFile.absolutePath, emptyMap())
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val snapshots = shared.parseSnapshotsDb(dbFile); dbFile.delete(); snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshotsFromFtp 异常", e); emptyList()
        } finally { stopServe(session) }
    }

    // restoreSnapshotFromFtp —— 对应 restoreSnapshotFromSftp
    suspend fun restoreSnapshotFromFtp(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticFtpRoot", "restoreFtp restUrl=${session.restUrl}")
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
                repositoryPath = session.restUrl, password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = emptyMap(), includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "restoreSnapshotFromFtp 异常", e)
            false
        } finally {
            stopServe(session)
        }
    }

    // forgetSnapshotFromFtp —— 对应 forgetSnapshotFromSftp
    suspend fun forgetSnapshotFromFtp(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticFtpRoot", "forgetFtp restUrl=${session.restUrl} snapshotId=$snapshotId")
            shared.rootService.forgetRusticSnapshot(session.restUrl, password, snapshotId, emptyMap()).isSuccess
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "forgetSnapshotFromFtp 异常", e)
            false
        } finally {
            stopServe(session)
        }
    }

    // pruneFtpRepository —— 对应 pruneSftpRepository（--max-unused unlimited）
    suspend fun pruneFtpRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticFtpRoot", "pruneFtp restUrl=${session.restUrl}")
            shared.rootService.pruneRusticRepository(session.restUrl, password, "unlimited", emptyMap(), instantDelete = true).isSuccess
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "pruneFtpRepository 异常", e)
            false
        } finally {
            stopServe(session)
        }
    }

    // listBackedUpFilesFromFtpWithSqlJni —— 对应 listBackedUpFilesFromSftpWithSqlJni
    suspend fun listBackedUpFilesFromFtpWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticFtpRoot", "listFilesFtp restUrl=${session.restUrl}")
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_ftp_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb(session.restUrl, password, dbFile.absolutePath, emptyMap())
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listBackedUpFilesFromFtpWithSqlJni 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    // 只读缓存：直接读持久 db，不起 serve、不走 JNI（毫秒级）
    fun readCachedApps(cloudEntity: CloudEntity): List<ResticBackupApp> =
        shared.readCachedApps(cloudEntity.name)

    // 重建：起 serve 拿 restUrl，走 JNI 重建持久 db 后解析返回
    suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.d(ResticShared.TAG, "refreshAndListApps (FTP) restUrl=${session.restUrl}")
            shared.refreshAppsDb(
                accountId = cloudEntity.name,
                repoPath = session.restUrl,
                password = password,
                options = emptyMap(),
            )
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (FTP) 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    // 保留旧名作为别名，避免破坏既有调用点
    suspend fun listBackedUpAppsFromFtpWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = refreshAndListApps(cloudEntity, password)

    /**
     * 起 librclone serve restic：把 FTP 表单字段（host/port/user/pass + remotePath）交给
     * 接入层，由 rclone 在 serve 侧完成 FTP + 密码认证，返回本地 rest: URL 与 serve id。
     * 端口为 localhost:0 动态分配，session 只在本次调用内有效。
     */
    private suspend fun startServe(cloudEntity: CloudEntity, remotePath: String): RcloneServe.Session =
        rcloneServe.start(cloudEntity, remotePath)

    /** 收尾：停掉本次 serve（传 id）。异常吞掉，避免影响主流程结果。 */
    private suspend fun stopServe(session: RcloneServe.Session) {
        try {
            rcloneServe.stop(session.id)
        } catch (e: Exception) {
            Log.w("ResticFtpRoot", "rcloneServeStop 失败 id=${session.id}", e)
        }
    }
}