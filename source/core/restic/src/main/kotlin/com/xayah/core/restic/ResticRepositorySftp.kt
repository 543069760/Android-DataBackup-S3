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
 * SFTP 备份的 JNI 实现（纯 JNI，不依赖 restic 二进制），结构对照 ResticRepositoryFtp。
 *
 * 与 FTP 的三点区别：
 *  1) repositoryPath 使用 librclone serve restic 起服务后返回的 rest: URL（session.restUrl），
 *     而不是 opendal:ftp；
 *  2) options 一律传 emptyMap()——SFTP 的 host/port/user/pass 认证由 rclone 在 serve 侧完成，
 *     rustic 侧只当作普通 REST 后端，不再需要 opendal 后端 options；
 *  3) 每个方法都用 serve 生命周期包住：start() 起服务拿端口，finally { stop(id) } 收尾。
 *     serve 必须在整批 JNI 调用期间一直存活，端口为 localhost:0 动态分配、不可跨会话复用。
 *
 * 说明：restic 仓库密码单独作为 password 参数传 JNI（与 FTP 一致）。
 */
@Singleton
class ResticRepositorySftp @Inject constructor(
    private val shared: ResticShared,
    private val rcloneServe: RcloneServe,
) {
    // initSftpRepository —— 对应 initFtpRepository
    suspend fun initSftpRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)
        try {
            Log.i("ResticSftpRoot", "initSftp restUrl=${session.restUrl} remotePath=$remotePath")

            // 1) 建库
            val initResult = shared.rootService.initRusticRepository(session.restUrl, password, emptyMap())
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    Exception(initResult.exceptionOrNull()?.message ?: "Unknown error during rustic init")
                )
            }

            // 2) 复核：确认仓库 config 真的写进了 SFTP
            val exists = shared.rootService.rusticRepositoryExists(session.restUrl, emptyMap())
            if (!exists) {
                Log.e(ResticShared.TAG, "SFTP init 报成功但 repositoryExists=false")
                return@withContext Result.failure(
                    Exception("init 报成功但仓库 config 未写入 SFTP（疑似 rclone serve/认证失败）")
                )
            }

            Log.d(ResticShared.TAG, "SFTP repository initialized and verified")
            Result.success("SFTP repository initialized")
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "initSftpRepository 异常", e)
            Result.failure(e)
        } finally {
            stopServe(session)
        }
    }

    // backupFileToSftp —— 对应 backupFileToFtp，返回 Pair<Int,String>（保持上层契约）
    suspend fun backupFileToSftp(
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
            Log.i("ResticSftpRoot", "backupSftp restUrl=${session.restUrl} remotePath=$remotePath")

            // 关键：每次备份各起一个 serve，但 rclone accounting 是进程级累计的，
            // 必须先 reset 出干净基准，否则 core/stats 会带上历史字节。
            rcloneServe.resetStats()

            // 起轮询协程：从 rclone core/stats 读实时累计字节 + 速度，驱动进度显示。
            // rest: 路径下 rustic 的写出计数只能按 pack 阶梯跳动，改由 rclone 侧
            // RcatSize 流式 accounting 提供 sub-pack 平滑进度。
            if (progressCallback != null) {
                pollJob = CoroutineScope(coroutineContext).launch {
                    while (polling.get() && isActive) {
                        val (bytes, speed) = rcloneServe.readStatsBytesAndSpeed()
                        Log.d("SftpStatsPoll", "poll bytes=$bytes speed=$speed")
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

            // ICallback 仍需传给 createRusticSnapshot 以维持回调链路，但 onProgress
            // 不再驱动进度显示（避免与轮询两路互相覆盖）。取消走 cancelId，与本回调无关。
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
                Log.d(ResticShared.TAG, "backupFileToSftp 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToSftp 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToSftp cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToSftp failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        } finally {
            // 先停轮询、等它干净结束，再停 serve，避免 serve 停后轮询仍打 RPC。
            polling.set(false)
            runCatching { pollJob?.cancelAndJoin() }
            stopServe(session)
        }
    }

    // restoreSnapshotFromSftp —— 对应 restoreSnapshotFromFtp
    suspend fun restoreSnapshotFromSftp(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticSftpRoot", "restoreSftp restUrl=${session.restUrl}")
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
        } catch (e: Exception) { false } finally {
            stopServe(session)
        }
    }

    // forgetSnapshotFromSftp —— 对应 forgetSnapshotFromFtp
    suspend fun forgetSnapshotFromSftp(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            shared.rootService.forgetRusticSnapshot(session.restUrl, password, snapshotId, emptyMap()).isSuccess
        } catch (e: Exception) { false } finally {
            stopServe(session)
        }
    }

    // pruneSftpRepository —— 对应 pruneFtpRepository（--max-unused unlimited）
    suspend fun pruneSftpRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            shared.rootService.pruneRusticRepository(session.restUrl, password, "unlimited", emptyMap(), instantDelete = true).isSuccess
        } catch (e: Exception) { false } finally {
            stopServe(session)
        }
    }

    // listBackedUpFilesFromSftpWithSqlJni —— 对应 listBackedUpFilesFromFtpWithSqlJni
    suspend fun listBackedUpFilesFromSftpWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_sftp_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb(session.restUrl, password, dbFile.absolutePath, emptyMap())
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() } finally {
            stopServe(session)
        }
    }

    fun readCachedApps(cloudEntity: CloudEntity): List<ResticBackupApp> =
        shared.readCachedApps(cloudEntity.name)

    suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.d(ResticShared.TAG, "refreshAndListApps (SFTP) restUrl=${session.restUrl}")
            shared.refreshAppsDb(
                accountId = cloudEntity.name,
                repoPath = session.restUrl,
                password = password,
                options = emptyMap(),
            )
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (SFTP) 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    suspend fun listBackedUpAppsFromSftpWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = refreshAndListApps(cloudEntity, password)

    // listSnapshotsFromSftp —— 对应 listBackedUpAppsFromSftpWithSqlJni，但用 parseSnapshotsDb（不按四段式过滤，保留 __icons__ 快照）
    suspend fun listSnapshotsFromSftp(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val repoPath = session.restUrl

            val sqlDir = File(shared.context.cacheDir, "sql")
            if (!sqlDir.exists()) sqlDir.mkdirs()

            val dbFile = File(sqlDir, "snapshots_sftp_${System.currentTimeMillis()}.db")

            Log.d(ResticShared.TAG, "执行 JNI listSnapshotsDb (SFTP/snapshots)，repo=$repoPath，输出=${dbFile.absolutePath}")

            val result = shared.rootService.listRusticSnapshotsDb(
                repositoryPath = repoPath,
                password = password,
                dbPath = dbFile.absolutePath,
                options = emptyMap(),
            )
            if (result.isFailure) {
                Log.e(ResticShared.TAG, "listRusticSnapshotsDb (SFTP/snapshots) 失败", result.exceptionOrNull())
                return@withContext emptyList()
            }
            if (!dbFile.exists() || dbFile.length() == 0L) {
                Log.e(ResticShared.TAG, "DB 文件未生成或为空 (SFTP/snapshots)")
                return@withContext emptyList()
            }

            val snapshots = shared.parseSnapshotsDb(dbFile)
            dbFile.delete()
            Log.d(ResticShared.TAG, "JNI 模式 (SFTP) 成功提取 ${snapshots.size} 个快照")
            snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshotsFromSftp 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    /**
     * 起 librclone serve restic：把 SFTP 表单字段（host/port/user/pass + remotePath）交给
     * 接入层，由 rclone 在 serve 侧完成 SFTP + 密码认证，返回本地 rest: URL 与 serve id。
     * 端口为 localhost:0 动态分配，session 只在本次调用内有效。
     */
    private suspend fun startServe(cloudEntity: CloudEntity, remotePath: String): RcloneServe.Session =
        rcloneServe.start(cloudEntity, remotePath)

    /** 收尾：停掉本次 serve（传 id）。异常吞掉，避免影响主流程结果。 */
    private suspend fun stopServe(session: RcloneServe.Session) {
        try {
            rcloneServe.stop(session.id)
        } catch (e: Exception) {
            Log.w("ResticSftpRoot", "rcloneServeStop 失败 id=${session.id}", e)
        }
    }
}