package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCleanRestoring
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class MediumRestoreUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "MediumRestoreUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private fun TaskDetailMediaEntity.getLog() = mediaInfo.log

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

    suspend fun restoreMedia(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String): ShellResult = run {
        log { "Restoring ${DataType.MEDIA_MEDIA.type}..." }

        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        val dst = m.path
        val dstDir = PathUtil.getParentPath(dst)
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        // Return if the archive doesn't exist.
        if (rootService.exists(src)) {
            val sizeBytes = rootService.calculateSize(src)
            t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)

            // Decompress the archive.
            Tar.decompress(
                exclusionList = listOf(),
                clear = if (context.readCleanRestoring().first()) "--recursive-unlink" else "",
                m = false,
                src = src,
                dst = dstDir,
                extra = ct.decompressPara
            ).also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)
            }
        } else {
            isSuccess = false
            out.add(log { "Not exist: $src" })
            t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
            return@run ShellResult(code = -1, input = listOf(), out = out)
        }
        t.updateInfo(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun download(
        client: CloudClient,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        dataType: DataType,
        srcDir: String,
        dstDir: String,
        onDownloaded: suspend (m: MediaEntity, t: TaskDetailMediaEntity, path: String) -> Unit
    ) = run {
        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)

        t.updateInfo(state = OperationState.DOWNLOADING)
        if (client.exists(src)) {
            var flag = true
            var progress = 0.0
            var total = 0.0
            var lastWritten = 0L
            var lastTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()  // 添加开始时间,用于计算平均速度

            with(CoroutineScope(coroutineContext)) {
                launch {
                    while (flag) {
                        delay(300)
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val bytesDiff = progress.toLong() - lastWritten
                        val speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                        val speedText = speed.toDouble().formatSize()
                        val percentage = if (total > 0) (progress / total * 100).toInt() else 0

                        // 只在速度大于0或者是第一次更新时才显示
                        if (speed > 0 || lastWritten == 0L) {
                            t.updateInfo(content = "$speedText/s | $percentage%")
                        }

                        lastWritten = progress.toLong()
                        lastTime = currentTime
                    }
                }
            }

            cloudRepository.download(client = client,
                src = src,
                dstDir = dstDir,
                onDownloading = { written, totalBytes ->  // 接收两个参数
                    progress = written.toDouble()
                    total = totalBytes.toDouble()
                },
                onDownloaded = {
                    onDownloaded(m, t, dstDir)
                }
            ).apply {
                flag = false

                // 计算整个下载过程的平均速度
                val totalTime = System.currentTimeMillis() - startTime
                val avgSpeed = if (totalTime > 0) (progress.toLong() * 1000 / totalTime) else 0L
                val avgSpeedText = avgSpeed.toDouble().formatSize()

                t.updateInfo(
                    log = (t.getLog() + "\n${outString}").trim(),
                    content = "$avgSpeedText/s | 100%"  // 显示平均速度和100%
                )
                if (isSuccess.not()) {
                    t.updateInfo(state = OperationState.ERROR)
                }
            }
        } else {
            if (dataType == DataType.PACKAGE_USER || dataType == DataType.PACKAGE_APK) {
                t.updateInfo(state = OperationState.ERROR, log = log { "Failed to connect to cloud or file not exist: $src" })
            } else {
                t.updateInfo(state = OperationState.SKIP, log = log { "Failed to connect to cloud or file not exist, skip: $src" })
            }
        }
    }
}
