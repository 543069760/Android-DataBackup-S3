package com.xayah.core.restic

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.util.encodeAccountId
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地备份与 COS/S3 备份共用的底层能力集合。
 * 由 ResticRepositoryLocal / ResticRepositoryCos 通过构造注入组合使用。
 */
@Singleton
class ResticShared @Inject constructor(
    @ApplicationContext val context: Context,
    val logger: ResticLogger,
    val resticNative: ResticNative,
    val rootService: RemoteRootService,
) {
    companion object {
        const val TAG = "ResticRepository"
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    val resticPath: String by lazy {
        resticNative.getResticBinaryPath(context)
    }

    /**
     * 核心执行方法:使用 libsu 执行 Root 命令
     */
    suspend fun executeRestic(
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        usePty: Boolean = false
    ): Shell.Result = withContext(Dispatchers.IO) {
        val defaultEnv = mutableMapOf(
            "HOME" to context.filesDir.absolutePath,
            "XDG_CACHE_HOME" to File(context.cacheDir, "restic").absolutePath
        )

        if (usePty) {
            defaultEnv["TERM"] = "xterm-256color"
        }

        defaultEnv.putAll(env)

        val envExports = defaultEnv.map { "export ${it.key}=\"${it.value}\"" }
        val resticCommand = "$resticPath ${args.joinToString(" ")}"

        val finalCommand = if (usePty) {
            val busyboxPath = "${context.filesDir.absolutePath}/bin/busybox"
            envExports.joinToString(" && ") +
                    " && $busyboxPath script -qc \"$resticCommand 2>&1\" /dev/null < /dev/null 2>&1"
        } else {
            envExports.joinToString(" && ") + " && $resticCommand"
        }

        Log.d(TAG, "=== Restic Command Debug ===")
        Log.d(TAG, "Command: restic ${args.joinToString(" ")}")
        Log.d(TAG, "Use PTY: $usePty")
        Log.d(TAG, "Environment: ${defaultEnv.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        Log.d(TAG, "Full command: $finalCommand")

        val result = Shell.cmd(finalCommand).exec()

        Log.d(TAG, "Exit code: ${result.code}")
        if (result.out.isNotEmpty()) {
            Log.d(TAG, "STDOUT:\n${result.out.joinToString("\n")}")
        }
        if (result.err.isNotEmpty()) {
            Log.e(TAG, "STDERR:\n${result.err.joinToString("\n")}")
        }
        Log.d(TAG, "==============================")

        result
    }

    /** 格式化 OpenDAL Root 路径,确保以 / 开头以 / 结尾 */
    fun formatOpenDALRoot(remotePath: String): String {
        val trimmed = remotePath.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return "/"
        }
        val withLeading = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withLeading.endsWith("/")) withLeading else "$withLeading/"
    }

    /**
     * SFTP 专用 root：rclone connection string 的路径区里，前导 "/" = 文件系统根，
     * 无前导 = 登录用户家目录相对。为与路径选择器（handleOriginalPath 加 "." 浏览家目录）
     * 保持一致，这里统一规整为“家目录相对”：去掉前导 "/" 与 "./"，去掉尾部 "/"。
     * 空路径返回 ""（= rclone 家目录本身）。
     */
    fun formatSftpRoot(remotePath: String): String {
        var s = remotePath.trim()
        // 去掉选择器可能带的 "./" 前缀
        while (s.startsWith("./")) s = s.removePrefix("./")
        // 去掉所有前导 "/"，使其成为家目录相对
        s = s.trimStart('/')
        // 去掉尾部 "/"
        s = s.trimEnd('/')
        return s
    }

    /** 构建 OpenDAL FTP Endpoint，格式: ftp://host:port（opendal FtpConfig.endpoint 需带 scheme 前缀） */
    fun buildOpenDALFtpEndpoint(host: String, port: Int): String {
        val cleanHost = host.trim()
            .removePrefix("ftp://")
            .removePrefix("ftps://")
            .removeSuffix("/")
        return "ftp://$cleanHost:$port"
    }

    /** 构建 OpenDAL WebDAV Endpoint：host 本身即完整 URL（含 http(s)://），仅做 trim + 去尾部斜杠，不改 scheme。 */
    fun buildOpenDALWebdavEndpoint(host: String): String {
        return host.trim().removeSuffix("/")
    }

    /** 构建 OpenDAL Endpoint,格式: protocol://endpoint (不含 bucket) */
    fun buildOpenDALEndpoint(extra: S3Extra): String {
        val protocol = when (extra.protocol) {
            S3Protocol.HTTP -> "http"
            S3Protocol.HTTPS -> "https"
        }
        return "$protocol://${extra.endpoint.trim().removeSuffix("/")}"
    }

    /** 从 rustic 生成的 .db 解析应用备份信息(v_snapshots_full 视图) */
    fun parseAppsDb(dbFile: File): List<ResticBackupApp> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT id, time, tags_flat, total_bytes_processed  
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
                                    packageName = packageName, userId = userId,
                                    timestamp = timestamp, dataType = dataType,
                                    snapshotId = snapshotId, snapshotTime = snapshotTime,
                                    tags = tags, totalBytesProcessed = totalBytesProcessed
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

    /** sanitize 规则与图标账号目录一致 */
    fun sanitizeAccountId(raw: String): String = encodeAccountId(raw)

    /** 持久化 db 缓存目录：filesDir/db_cache（不会被系统低存储时清理，cacheDir 会） */
    private fun dbCacheDir(): File = File(context.filesDir, "db_cache").apply { if (!exists()) mkdirs() }

    /** 按账号命名的持久 apps 缓存 db */
    fun appsDbFile(accountId: String): File = File(dbCacheDir(), "apps_${sanitizeAccountId(accountId)}.db")

    /** 只读：持久 db 存在且非空则直接解析返回；否则 emptyList。绝不触发任何 JNI/网络重建。 */
    fun readCachedApps(accountId: String): List<ResticBackupApp> {
        val f = appsDbFile(accountId)
        if (!f.exists() || f.length() == 0L) return emptyList()
        return try {
            parseAppsDb(f)
        } catch (e: Exception) {
            Log.e(TAG, "readCachedApps 解析失败 accountId=$accountId", e)
            emptyList()
        }
    }

    /**
     * 重建：JNI listRusticSnapshotsDb 先写临时文件，成功后原子 rename 覆盖持久 db，再解析返回。
     * 失败不破坏既有缓存，回退返回既有缓存内容（不再 delete 持久 db）。
     */
    suspend fun refreshAppsDb(
        accountId: String, repoPath: String, password: String, options: Map<String, String>
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        val target = appsDbFile(accountId)
        val tmp = File(target.parentFile, "${target.name}.tmp_${System.currentTimeMillis()}")
        try {
            val result = rootService.listRusticSnapshotsDb(repoPath, password, tmp.absolutePath, options)
            if (result.isFailure || !tmp.exists() || tmp.length() == 0L) {
                Log.e(TAG, "refreshAppsDb 重建失败 accountId=$accountId，回退旧缓存")
                tmp.delete()
                return@withContext readCachedApps(accountId)
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
            parseAppsDb(target)
        } catch (e: Exception) {
            Log.e(TAG, "refreshAppsDb 异常 accountId=$accountId", e)
            tmp.delete()
            readCachedApps(accountId)
        }
    }

    /** 从 .db 解析快照信息 */
    fun parseSnapshotsDb(dbFile: File): List<ResticSnapshot> {
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

    /** 从 .db 解析文件备份信息(v_snapshots_full 视图) */
    fun parseFilesDb(dbFile: File): List<ResticBackupFiles> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT id, time, paths_flat, tags_flat, total_bytes_processed  
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
                val paths = pathsFlat.split(31.toChar()).filter { it.isNotEmpty() }
                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }
                tags.forEach { tag ->
                    val parts = tag.split("-")
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
                                    mediaName = mediaName, fullPath = fullPath,
                                    timestamp = timestamp, dataType = dataType,
                                    snapshotId = snapshotId, snapshotTime = snapshotTime,
                                    tags = tags, totalBytesProcessed = totalBytesProcessed
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
}