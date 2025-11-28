package com.xayah.core.service.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext // 导入 Hilt @ApplicationContext
import javax.inject.Inject // 导入 @Inject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import kotlinx.coroutines.future.await
import com.xayah.core.service.util.ResticExecutor
// 假设这些数据类位于同一包下，确保导入它们以供使用
import com.xayah.core.service.util.ResticBackupProgress
import com.xayah.core.service.util.BackupResult

/**
 * Restic 备份操作的实用工具类。
 * 负责编排备份流程、处理取消逻辑，并提供统一的结果。
 * 通过 @Inject 标记，使其可被 Dagger/Hilt 注入。
 */
class ResticBackupUtil @Inject constructor(
    // 使用 @ApplicationContext 标记，告诉 Hilt 注入应用级别的 Context
    @ApplicationContext private val context: Context
) {
    // ResticExecutor 负责实际的命令执行。
    // 在此假设它仍需手动实例化并依赖 Context。
    private val resticExecutor = ResticExecutor(context)

    /**
     * 执行 Restic 备份操作。
     *
     * @param sourcePath 要备份的源路径。
     * @param localRepoPath Restic 仓库的路径。
     * @param password 仓库密码。
     * @param onProgress 实时进度更新回调（Float 0.0f - 100.0f）。
     * @param onCancel 用于触发取消操作的回调函数。
     * @return 备份操作的最终结果 (BackupResult)。
     */
    suspend fun backupWithRestic(
        sourcePath: String,
        localRepoPath: String,
        password: String,
        onProgress: (Float) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BackupResult {
        // 创建一个 CompletableFuture 作为取消信号
        val cancelFuture = CompletableFuture<Unit>()

        // 如果提供了取消回调，将其绑定到 cancelFuture 的完成事件
        onCancel?.let {
            cancelFuture.thenRun { it() }
        }

        return try {
            val summary = resticExecutor.executeBackup(
                repoPath = localRepoPath,
                password = password,
                sourcePath = sourcePath,
                cancel = cancelFuture
            ) { progress: ResticBackupProgress ->
                // 将 ResticExecutor 提供的 Double 进度转换为 Float 并通过回调报告
                onProgress(progress.percent_done.toFloat())
            }

            // 成功完成，返回快照 ID
            BackupResult.success(summary.snapshot_id.id)
        } catch (e: CancellationException) {
            // 捕获到取消异常，返回取消结果
            BackupResult.cancelled()
        } catch (e: Exception) {
            // 捕获其他运行时异常，返回失败结果
            BackupResult.failed(e.message ?: "Backup failed with an unknown error.")
        } finally {
            // 确保在操作完成（无论是成功、失败或取消）后，CompletableFuture 被完成，避免资源泄漏。
            if (!cancelFuture.isDone) {
                cancelFuture.complete(Unit)
            }
        }
    }
}