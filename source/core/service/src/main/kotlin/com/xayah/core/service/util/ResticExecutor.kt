package com.xayah.core.service.util

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import java.lang.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
// 修复点 1: 确保导入了 Json 构造器和配置属性
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.decodeFromString
import com.xayah.core.common.restic.ResticBinaryManager

class ResticExecutor(private val context: Context) {
    // 修复点 2: 确保 Json 初始化配置正确。
    // 在 Kotlin 1.6+ 和 kotlinx.serialization 1.3+ 中，这种写法通常需要显式导入配置属性，
    // 但为了兼容性，我们确保 Json 导入正确。
    private val jsonFormat = Json { ignoreUnknownKeys = true }
    private val storageDir = File(context.filesDir, "restic")
    private val binaryManager = ResticBinaryManager(context)

    init {
        storageDir.mkdirs()
    }

    suspend fun executeBackup(
        repoPath: String,
        password: String,
        sourcePath: String,
        cancel: CompletableFuture<Unit>,
        onProgress: (ResticBackupProgress) -> Unit
    ): ResticBackupSummary {
        return withContext(Dispatchers.IO) {
            val process: Process? = null
            try {
                // 确保二进制可用
                if (!binaryManager.ensureBinariesAvailable()) {
                    throw ResticException(0, listOf("Failed to ensure restic binary available"))
                }

                val command = listOf(
                    binaryManager.getResticPath(),
                    "--json",
                    "backup",
                    sourcePath
                )

                val processBuilder = ProcessBuilder(command)
                    .directory(storageDir)
                    .redirectErrorStream(true)

                val process = processBuilder.start()

                // 设置取消处理
                cancel.thenRun {
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                }

                var summary: ResticBackupSummary? = null

                process.inputStream.bufferedReader(Charset.defaultCharset()).use { reader ->
                    reader.forEachLine { line: String ->
                        try {
                            when {
                                line.contains("\"message_type\":\"status\"") -> {
                                    val progress = jsonFormat.decodeFromString<ResticBackupProgress>(line)
                                    onProgress(progress)
                                }
                                line.contains("\"message_type\":\"summary\"") -> {
                                    summary = jsonFormat.decodeFromString<ResticBackupSummary>(line)
                                }
                            }
                        } catch (e: Exception) {
                            // 忽略解析错误，继续处理下一行
                        }
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw ResticException(exitCode, emptyList())
                }

                summary ?: throw ResticException(0, listOf("No summary received from restic"))
            } catch (e: Exception) {
                process?.destroyForcibly()
                throw e
            }
        }
    }
}

// 数据类定义保持不变
@Serializable
data class ResticBackupProgress(
    val message_type: String,
    val percent_done: Double,
    val bytes_done: Long,
    val total_bytes: Long?,
    val files_done: Int,
    val total_files: Int?,
    val seconds_elapsed: Long
)

@Serializable
data class ResticBackupSummary(
    val message_type: String,
    val snapshot_id: ResticSnapshotId,
    val files_new: Long,
    val files_changed: Long,
    val files_unmodified: Long
)

@Serializable
data class ResticSnapshotId(val id: String)

class ResticException(
    val exitCode: Int,
    val output: List<String>,
    val cancelled: Boolean = false
) : Exception("Restic failed with exit code $exitCode")