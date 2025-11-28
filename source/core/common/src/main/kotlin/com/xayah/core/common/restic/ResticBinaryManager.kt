package com.xayah.core.common.restic

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ResticBinaryManager(private val context: Context) {
    companion object {
        private const val RESTIC_VERSION = "0.18.1"
        private const val RESTIC_BINARY_NAME = "restic"
    }

    private val binariesDir = File(context.filesDir, "restic-binaries")

    init {
        binariesDir.mkdirs()
    }

    suspend fun ensureBinariesAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 检查 restic 二进制是否存在
                if (!isResticBinaryAvailable()) {
                    // 下载 restic 二进制
                    downloadResticBinary()
                }
                // 设置执行权限
                setExecutablePermissions()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun isResticBinaryAvailable(): Boolean {
        val resticFile = File(binariesDir, RESTIC_BINARY_NAME)
        return resticFile.exists() && resticFile.canExecute()
    }

    private fun downloadResticBinary(): Boolean {
        return try {
            val arch = getCurrentArchitecture()
            val resticUrl = "https://github.com/restic/restic/releases/download/v${RESTIC_VERSION}/restic_${RESTIC_VERSION}_linux_${arch}.bz2"

            // 下载压缩文件
            val url = URL(resticUrl)
            val connection = url.openConnection()
            connection.connect()

            // 解压并保存
            url.openStream().use { inputStream ->
                val process = ProcessBuilder("bzip2", "-dc").start()
                FileOutputStream(File(binariesDir, RESTIC_BINARY_NAME)).use { outputStream ->
                    inputStream.copyTo(process.outputStream)
                    process.inputStream.copyTo(outputStream)
                }
                process.waitFor()
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentArchitecture(): String {
        return when (android.os.Build.SUPPORTED_ABIS.first()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "amd64"
            "x86" -> "386"
            else -> "arm64"
        }
    }

    private fun setExecutablePermissions() {
        File(binariesDir, RESTIC_BINARY_NAME).setExecutable(true, false)
    }

    fun getResticPath(): String = File(binariesDir, RESTIC_BINARY_NAME).absolutePath
}