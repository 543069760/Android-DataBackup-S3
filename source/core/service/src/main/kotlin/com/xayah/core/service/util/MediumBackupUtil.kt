package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withLog
import dagger.hilt.android.qualifiers.ApplicationContext // 关键修复：导入 ApplicationContext 注解
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

// 移除了不必要的 AndroidEntryPoint 导入，因为它不是 Hilt 的入口点
class MediumBackupUtil @Inject constructor(
    // 关键修复：只保留一个 context 参数，并添加 @ApplicationContext 注解
    @ApplicationContext private val context: Context,
    private val pathUtil: PathUtil,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val cloudRepository: CloudRepository,
) {
    private fun log(msg: () -> String): String = run {
        LogUtil.log { "MediumBackupUtil" to msg() }
        msg()
    }

    suspend fun backupMedia(
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        r: MediaEntity?,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null
    ): BackupResult = run {
        log { "Backing up ${DataType.MEDIA_MEDIA.type}..." }

        val name = m.name
        val src = m.path
        var isSuccess = true
        val outString = StringBuilder()

        // 修复点: 使用 MediaEntity 自身的 activated 状态
        if (!getDataSelected(m)) {
            return BackupResult.success("")
        }

        if (src.isEmpty()) {
            return BackupResult.failed("Failed to get media path")
        }

        val ct = m.indexInfo.compressionType
        val resticUtil = ResticBackupUtil(context)
        val localRepoPath = getLocalResticRepoPath()

        // 关键修复点: 获取当前协程的 Scope，用于启动非阻塞的更新任务
        val currentScope = CoroutineScope(coroutineContext)

        return resticUtil.backupWithRestic(
            sourcePath = src,
            localRepoPath = localRepoPath,
            password = getResticPassword(),
            onProgress = { progress: Float ->
                // 关键修复点: 使用 launch 启动非阻塞更新，并调用扩展函数
                currentScope.launch {
                    t.update(progress = progress) // <--- 调用扩展函数
                }
            },
            onCancel = {
                if (isCanceled?.invoke() == true) {
                    throw CancellationException()
                }
            }
        )
    }

    suspend fun upload(
        client: CloudClient,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        srcDir: String,
        dstDir: String,
        customFileName: String? = null,
        isCanceled: (() -> Boolean)? = null
    ): ShellResult {
        val ct = m.indexInfo.compressionType
        val src = if (customFileName != null) {
            File(srcDir, customFileName).absolutePath
        } else {
            mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        }

        // 关键修复点: 获取当前协程的 Scope
        val currentScope = CoroutineScope(coroutineContext)

        return cloudRepository.upload(
            client = client,
            src = src,
            dstDir = dstDir,
            onUploading = { read, total ->
                val progress = read.toFloat() / total
                // 关键修复点: 使用 launch 启动非阻塞更新，并调用扩展函数
                currentScope.launch {
                    t.update(progress = progress) // <--- 调用扩展函数
                }
            },
            isCanceled = isCanceled
        )
    }

    private fun getLocalResticRepoPath(): String {
        return File(context.filesDir, "restic-repo").absolutePath
    }

    // 修复点: 移除了 suspend 关键字，并直接访问 MediaEntity 的 activated 状态
    private fun getDataSelected(m: MediaEntity): Boolean {
        return m.extraInfo.activated
    }

    private fun getResticPassword(): String {
        return "restic-backup-password"
    }

    private fun TaskDetailMediaEntity.getLog() = mediaInfo.log

    /**
     * 扩展函数：更新任务的详细信息，例如状态、字节数、日志或内容。
     */
    private suspend fun TaskDetailMediaEntity.updateInfo(
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        mediaInfo.also {
            if (state != null) it.state = state
            if (bytes != null) it.bytes = bytes
            if (log != null) it.log = log
            if (content != null) it.content = content
        }
        taskDao.upsert(this)
    }

    /**
     * 扩展函数：更新任务的进度。
     */
    private suspend fun TaskDetailMediaEntity.update(progress: Float) {
        mediaInfo.progress = progress
        taskDao.upsert(this)
    }
}