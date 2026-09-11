package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.datastore.readFtpResticPassword
import com.xayah.core.datastore.readWebdavResticPassword
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.CloudType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepositoryCos
import com.xayah.core.restic.ResticRepositoryFtp
import com.xayah.core.restic.ResticRepositoryWebdav
import com.xayah.core.restic.ResticRepositorySftp
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.decodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.datastore.readLoadedIconMD5
import com.xayah.core.datastore.saveLoadedIconMD5
import com.xayah.core.util.iconDir
import com.xayah.core.util.PathUtil
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.util.encodeAccountId
import com.xayah.core.model.CompressionType
import com.xayah.core.util.command.Tar
import com.xayah.feature.main.restore.R
import com.xayah.core.model.restic.ResticRestoreQueueItem
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.system.measureTimeMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import javax.inject.Inject
import java.io.File

@HiltViewModel
class CloudRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoCos: ResticRepositoryCos,
    private val resticRepoFtp: ResticRepositoryFtp,
    private val resticRepoWebdav: ResticRepositoryWebdav,
    private val resticRepoSftp: ResticRepositorySftp,
    private val appsDao: PackageDao,
    private val appsRepo: com.xayah.core.data.repository.AppsRepo,
    private val rootService: RemoteRootService,
    private val cloudRepo: CloudRepository
) : ViewModel() {

    companion object {
        const val RESTORE_QUEUE_FILE = "restic_restore_queue.json"
        private const val TAG = "CloudRestore"
    }

    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    private var accountName: String = ""
    private val _uiState = MutableStateFlow<CloudRestoreUiState>(CloudRestoreUiState.Loading)
    val uiState: StateFlow<CloudRestoreUiState> = _uiState.asStateFlow()

    /**
     * 批量恢复准备（云端 restic）：逐包只解出 config 并校验，仅把通过的包写入队列 + 激活到 DB。
     * 重型 tar 仍由服务层 onBeforeRestorePackage 解出。单个包失败只跳过该包，不影响其余包。
     * @return 至少有一个包成功返回 true；全部失败返回 false。
     */
    suspend fun prepareBatchRestore(groups: List<ResticBackupGroup>): Boolean = withContext(Dispatchers.IO) {
        try {
            val cloudEntity = cloudRepo.queryByName(accountName)
            if (cloudEntity == null) {
                Log.e(TAG, "prepareBatchRestore: 云端账户查询失败: $accountName")
                return@withContext false
            }
            val password = resolveResticPassword(cloudEntity)
            if (password.isNullOrEmpty()) {
                Log.e(TAG, "prepareBatchRestore: restic 密码未配置")
                return@withContext false
            }

            val targetBasePath = "${context.localBackupSaveDir()}/restore/"
            val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
            val appsDir = File("${targetBasePath}apps")
            if (appsDir.exists()) {
                Log.d(TAG, "prepareBatchRestore: 清空上次残留中转目录: ${appsDir.path}")
                runCatching { appsDir.deleteRecursively() }
                    .onFailure { Log.w(TAG, "prepareBatchRestore: 清空 apps 目录失败(忽略): ${it.message}") }
            }

            // 每个 group 的解 config 结果：成功则返回 group，失败则返回 packageName
            data class PrepareResult(val group: ResticBackupGroup, val ok: Boolean)

            // 受限并发：最多同时 4 个包解 config，避免云端连接数过高触发限流/失败
            val semaphore = Semaphore(permits = 4)

            val results: List<PrepareResult>
            val elapsed = measureTimeMillis {
                results = coroutineScope {
                    groups.map { group ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                // 只取该包的 CONFIG 快照
                                val configBackup = group.backups.firstOrNull { it.dataType == DataType.PACKAGE_CONFIG }
                                if (configBackup == null) {
                                    Log.w(TAG, "prepareBatchRestore: ${group.packageName} 无 config 快照，跳过")
                                    return@withPermit PrepareResult(group, false)
                                }

                                val snapshotSubPath = "$backupBaseDir/apps/${group.packageName}/user_${group.userId}"
                                val fullTargetPath = "${targetBasePath}apps/${group.packageName}/user_${group.userId}/"

                                // 云端按 CloudType 分派解出 config
                                val ok = runCatching {
                                    when (cloudEntity.type) {
                                        CloudType.FTP -> resticRepoFtp.restoreSnapshotFromFtp(
                                            cloudEntity = cloudEntity, password = password,
                                            snapshotId = configBackup.snapshotId,
                                            targetPath = fullTargetPath,
                                            snapshotSubPath = snapshotSubPath,
                                            includePath = "package_restore_config.json"
                                        )
                                        CloudType.WEBDAV -> resticRepoWebdav.restoreSnapshotFromWebdav(
                                            cloudEntity = cloudEntity, password = password,
                                            snapshotId = configBackup.snapshotId,
                                            targetPath = fullTargetPath,
                                            snapshotSubPath = snapshotSubPath,
                                            includePath = "package_restore_config.json"
                                        )
                                        CloudType.SFTP -> resticRepoSftp.restoreSnapshotFromSftp(
                                            cloudEntity = cloudEntity, password = password,
                                            snapshotId = configBackup.snapshotId,
                                            targetPath = fullTargetPath,
                                            snapshotSubPath = snapshotSubPath,
                                            includePath = "package_restore_config.json"
                                        )
                                        else -> resticRepoCos.restoreSnapshotFromCos(
                                            cloudEntity = cloudEntity, password = password,
                                            snapshotId = configBackup.snapshotId,
                                            targetPath = fullTargetPath,
                                            snapshotSubPath = snapshotSubPath,
                                            includePath = "package_restore_config.json"
                                        )
                                    }
                                }.getOrElse { e ->
                                    Log.e(TAG, "prepareBatchRestore: ${group.packageName} config 解出异常: ${e.message}", e)
                                    false
                                }

                                // 校验落盘的 config 文件存在
                                val configExists = File(fullTargetPath, "package_restore_config.json").exists()
                                if (!ok || !configExists) {
                                    Log.e(TAG, "prepareBatchRestore: ${group.packageName} config 解出/校验失败，跳过并清理")
                                    runCatching { File(fullTargetPath).deleteRecursively() }
                                    return@withPermit PrepareResult(group, false)
                                }

                                PrepareResult(group, true)
                            }
                        }
                    }.awaitAll()
                }
            }
            Log.d(TAG, "耗时/prepareBatchRestore.并发解config: ${elapsed}ms, count=${groups.size}")

            // awaitAll 之后单线程汇总，避免并发写同一个 mutableList
            val succeededGroups = results.filter { it.ok }.map { it.group }
            val failedPackages = results.filter { !it.ok }.map { it.group.packageName }

            if (succeededGroups.isEmpty()) {
                Log.e(TAG, "prepareBatchRestore: 无任何包通过校验")
                return@withContext false
            }

            // 仅用通过校验的包扁平化写队列（含 CONFIG 与重型，accountName = 明文账户名）
            val queue = succeededGroups.flatMap { group ->
                group.backups.map { backup ->
                    ResticRestoreQueueItem(
                        packageName = backup.packageName,
                        userId = backup.userId,
                        dataType = backup.dataType,
                        snapshotId = backup.snapshotId,
                        accountName = cloudEntity.name   // 云端：原始明文账户名
                    )
                }
            }
            File(context.cacheDir, RESTORE_QUEUE_FILE).writeText(Json.encodeToString(queue))

            // 刷 DB（内部先删旧 RESTORE 记录，再扫已落盘 config 激活）+ 计算大小
            refreshLocalDatabase(targetBasePath)
            calculateSizesForActivatedApps()

            Log.d(TAG, "prepareBatchRestore: 准备完成，成功 ${succeededGroups.size} 个，跳过 ${failedPackages.size} 个：$failedPackages")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareBatchRestore 失败: ${e.message}", e)
            false
        }
    }

    // 图标版本信号：图标解压完成后自增，触发列表项 PackageIconImage 重新取图
    private val _iconVersion = MutableStateFlow(0)
    val iconVersion: StateFlow<Int> = _iconVersion.asStateFlow()

    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** 按账户类型解析 restic 仓库密码：账户级(extra) 优先，回落 DataStore 全局值 */
    private suspend fun resolveResticPassword(cloudEntity: CloudEntity): String? {
        return when (cloudEntity.type) {
            CloudType.FTP -> {
                val ftpExtra = runCatching { json.decodeFromString<FTPExtra>(cloudEntity.extra) }.getOrNull()
                ftpExtra?.resticPassword?.takeIf { it.isNotEmpty() } ?: context.readFtpResticPassword()
            }
            CloudType.WEBDAV -> {
                val webdavExtra = runCatching { json.decodeFromString<WebDAVExtra>(cloudEntity.extra) }.getOrNull()
                webdavExtra?.resticPassword?.takeIf { it.isNotEmpty() } ?: context.readWebdavResticPassword()
            }
            CloudType.SFTP -> {
                val sftpExtra = runCatching { json.decodeFromString<SFTPExtra>(cloudEntity.extra) }.getOrNull()
                sftpExtra?.resticPassword?.takeIf { it.isNotEmpty() }
            }
            else -> {
                val s3Extra = runCatching { json.decodeFromString<S3Extra>(cloudEntity.extra) }.getOrNull()
                s3Extra?.resticPassword?.takeIf { it.isNotEmpty() } ?: context.readS3ResticPassword()
            }
        }
    }

    fun setCloudEntity(accountName: String) {
        val cleanAccountName = accountName.replace("accountName=", "").decodeURL()
        this.accountName = cleanAccountName // 存储账户名
        Log.d("CloudRestore", "setCloudEntity 被调用，账户名: $accountName")
        viewModelScope.launch {
            Log.d("CloudRestore", "开始查询云端账户: $cleanAccountName")
            val cloudEntity = cloudRepo.queryByName(cleanAccountName)
            if (cloudEntity != null) {
                Log.d("CloudRestore", "找到云端账户: ${cloudEntity.name}, 类型: ${cloudEntity.type}")
                Log.d("CloudRestore", "准备调用 loadCloudBackedUpApps")
                loadCloudBackedUpApps(cloudEntity)
            } else {
                Log.e("CloudRestore", "云端账户查询失败: $cleanAccountName")
                _uiState.value = CloudRestoreUiState.Error(context.getString(R.string.restore_account_not_found, cleanAccountName))
            }
        }
    }

    private var loadedAccountId: String? = null
    fun loadCloudBackedUpApps(cloudEntity: CloudEntity, force: Boolean = false) {
        // 守卫：非强制、账号未变、已是 Success，直接复用
        if (!force
            && loadedAccountId == cloudEntity.name
            && _uiState.value is CloudRestoreUiState.Success) {
            return
        }
        viewModelScope.launch {
            val password = resolveResticPassword(cloudEntity)
            if (password.isNullOrEmpty()) {
                _uiState.value = CloudRestoreUiState.Error(context.getString(R.string.restore_password_not_configured))
                return@launch
            }
            val accountId = encodeAccountId(cloudEntity.name)

            // ---- 阶段一：读持久缓存（纯本地 SQLite 读，零网络），命中则秒开 ----
            val cachedApps: List<ResticBackupApp> = runCatching {
                when (cloudEntity.type) {
                    CloudType.FTP    -> resticRepoFtp.readCachedApps(cloudEntity)
                    CloudType.WEBDAV -> resticRepoWebdav.readCachedApps(cloudEntity)
                    CloudType.SFTP   -> resticRepoSftp.readCachedApps(cloudEntity)
                    else             -> resticRepoCos.readCachedApps(cloudEntity)
                }
            }.getOrElse { emptyList() }

            if (cachedApps.isNotEmpty()) {
                val labelMap = readLabelsMap(accountId)
                _uiState.value = CloudRestoreUiState.Success(buildGroupedBackups(cachedApps, labelMap))
                loadedAccountId = cloudEntity.name
                Log.d("CloudRestore", "缓存命中，秒开 ${cachedApps.size} 条 (accountId=$accountId)")
            } else {
                // 只有真正无缓存时才转圈
                _uiState.value = CloudRestoreUiState.Loading
            }

            // ---- 阶段二：后台重建（走 JNI open_repository + get_all_snapshots），完成后静默替换 ----
            try {
                val freshApps: List<ResticBackupApp> = when (cloudEntity.type) {
                    CloudType.FTP    -> resticRepoFtp.refreshAndListApps(cloudEntity, password)
                    CloudType.WEBDAV -> resticRepoWebdav.refreshAndListApps(cloudEntity, password)
                    CloudType.SFTP   -> resticRepoSftp.refreshAndListApps(cloudEntity, password)
                    else             -> resticRepoCos.refreshAndListApps(cloudEntity, password)
                }
                // 图标/labels 取回仍在重建后调用（保持原顺序）
                runCatching { loadCloudIconsFromRestic(cloudEntity, password) }
                    .onSuccess { _iconVersion.value++ }   // 图标已解压，触发列表重取图
                    .onFailure { Log.w("CloudRestore", "图标取回失败(忽略): ${it.message}") }

                val labelMap = readLabelsMap(accountId)
                _uiState.value = CloudRestoreUiState.Success(buildGroupedBackups(freshApps, labelMap))
                loadedAccountId = cloudEntity.name
                Log.d("CloudRestore", "后台刷新完成，静默替换 ${freshApps.size} 条")
            } catch (e: Exception) {
                // 缓存已展示则保留缓存、只记日志；无缓存才报错
                if (cachedApps.isEmpty()) {
                    _uiState.value = CloudRestoreUiState.Error(context.getString(R.string.restore_load_failed_reason, e.message ?: ""))
                } else {
                    Log.w("CloudRestore", "后台刷新失败，保留缓存列表: ${e.message}")
                }
            }
        }
    }

    fun forceReload() {
        val name = accountName ?: return
        viewModelScope.launch {
            val cloudEntity = cloudRepo.queryByName(name) ?: return@launch
            loadCloudBackedUpApps(cloudEntity, force = true)
        }
    }

    /** 把 ResticBackupApp 列表按 (userId, packageName, timestamp) 分组为 UI 列表 */
    private fun buildGroupedBackups(
        apps: List<ResticBackupApp>,
        labelMap: Map<String, String>
    ): List<ResticBackupGroup> =
        apps.groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }
            .map { entry ->
                val backupsInGroup = entry.value
                val first = backupsInGroup.first()
                val label = resolveAppLabel(labelMap, first.userId, first.packageName)
                Log.d("CloudRestore", "label映射 ${first.userId}-${first.packageName} -> $label")
                ResticBackupGroup(
                    packageName = first.packageName,
                    userId = first.userId,
                    timestamp = first.timestamp,
                    backups = backupsInGroup.sortedBy { backup ->
                        when (backup.dataType) {
                            DataType.PACKAGE_APK      -> 0
                            DataType.PACKAGE_USER     -> 1
                            DataType.PACKAGE_USER_DE  -> 2
                            DataType.PACKAGE_DATA     -> 3
                            DataType.PACKAGE_OBB      -> 4
                            DataType.PACKAGE_MEDIA    -> 5
                            DataType.PACKAGE_CONFIG   -> 6
                            else                      -> 7
                        }
                    },
                    appLabel = label
                )
            }
            .sortedByDescending { it.timestamp }

    private suspend fun loadCloudIconsFromRestic(cloudEntity: CloudEntity, password: String) = withContext(Dispatchers.IO) {
        val accountId = encodeAccountId(cloudEntity.name)
        Log.d("IconRestore", "cloud enter, accountId=$accountId, type=${cloudEntity.type}")

        try {
            // 1. 列快照，筛 __icons__-<accountId>- 前缀，取 time 最新
            val snapshots = when (cloudEntity.type) {
                CloudType.FTP    -> resticRepoFtp.listSnapshotsFromFtp(cloudEntity, password)
                CloudType.WEBDAV -> resticRepoWebdav.listSnapshotsFromWebdav(cloudEntity, password)
                CloudType.SFTP   -> resticRepoSftp.listSnapshotsFromSftp(cloudEntity, password)
                else             -> resticRepoCos.listSnapshotsFromCos(cloudEntity, password)
            }
            Log.d("IconRestore", "cloud snapshots total=${snapshots.size}")

            val matched = snapshots.filter { snap -> snap.tags.any { it.startsWith("__icons__-$accountId-") } }
            Log.d("IconRestore", "cloud icon snapshots matched=${matched.size} for prefix=__icons__-$accountId-")

            val iconSnapshot = matched.maxByOrNull { it.time }
            if (iconSnapshot == null) {
                Log.w("IconRestore", "cloud no icon snapshot found for accountId=$accountId")
                return@withContext
            }
            val snapshotId = iconSnapshot.id
            Log.d("IconRestore", "cloud selected snapshotId=$snapshotId, time=${iconSnapshot.time}, tags=${iconSnapshot.tags}")

            // 2. 去重：snapshotId 未变则跳过
            val loaded = context.readLoadedIconMD5(accountId)
            val iconDirForCheck = context.iconDir(accountId)
            val iconDirExists = File(iconDirForCheck).let { it.exists() && (it.listFiles()?.isNotEmpty() == true) }
            Log.d("IconRestore", "cloud dedup check, loaded=$loaded, current=$snapshotId, dirExists=$iconDirExists")
            if (loaded == snapshotId && iconDirExists) {
                Log.d("IconRestore", "cloud skip decompress, snapshotId unchanged ($snapshotId) and dir non-empty")
                return@withContext
            }
            if (loaded == snapshotId && !iconDirExists) {
                Log.d("IconRestore", "cloud token matched but icon dir missing/empty, force re-decompress ($snapshotId)")
            }

            // 3. 整快照还原到临时目录
            val tmpDir = File(context.cacheDir, "icon_restore_$accountId").apply { deleteRecursively(); mkdirs() }
            Log.d("IconRestore", "cloud restoring snapshot to tmpDir=${tmpDir.absolutePath}")
            val ok = when (cloudEntity.type) {
                CloudType.FTP    -> resticRepoFtp.restoreSnapshotFromFtp(cloudEntity, password, snapshotId, tmpDir.absolutePath)
                CloudType.WEBDAV -> resticRepoWebdav.restoreSnapshotFromWebdav(cloudEntity, password, snapshotId, tmpDir.absolutePath)
                CloudType.SFTP   -> resticRepoSftp.restoreSnapshotFromSftp(cloudEntity, password, snapshotId, tmpDir.absolutePath)
                else             -> resticRepoCos.restoreSnapshotFromCos(cloudEntity, password, snapshotId, tmpDir.absolutePath)
            }
            if (!ok) {
                Log.w("IconRestore", "cloud restore snapshot failed accountId=$accountId, snapshotId=$snapshotId")
                tmpDir.deleteRecursively()
                return@withContext
            }
            Log.d("IconRestore", "cloud restore snapshot ok, snapshotId=$snapshotId")

            // 4. 递归找 icon.tar
            val iconTar = tmpDir.walkTopDown().firstOrNull {
                it.isFile && it.name == "$IconRelativeDir.${CompressionType.TAR.suffix}"
            }
            if (iconTar == null) {
                Log.w("IconRestore", "cloud icon.tar not found in tmpDir=${tmpDir.absolutePath}")
                tmpDir.deleteRecursively()
                return@withContext
            }
            Log.d("IconRestore", "cloud found icon.tar=${iconTar.absolutePath}, size=${iconTar.length()}")

            // 5. 解压到按账号隔离的目录 + 修 SELinux + 记 snapshotId 去重 token
            val iconDst = context.iconDir(accountId)
            File(iconDst).mkdirs()
            Tar.decompress(
                cacheDir = context.cacheDir.path,
                callTar = { o, e, argv -> rootService.callTarCli(o, e, argv) },
                src = iconTar.absolutePath,
                dst = iconDst,
                stripComponents = 1,
            )
            PathUtil.setFilesDirSELinux(context)
            context.saveLoadedIconMD5(accountId, snapshotId)
            tmpDir.deleteRecursively()
            Log.d("IconRestore", "cloud icons decompressed to $iconDst, snapshotId=$snapshotId")
        } catch (e: Exception) {
            Log.e("IconRestore", "cloud icon restore failed accountId=$accountId", e)
        }
    }

    /** 读取 filesDir/icon/<accountId>/labels.json -> Map<"<userId>-<packageName>", label> */
    private fun readLabelsMap(accountId: String): Map<String, String> {
        return try {
            val labelsFile = File(context.iconDir(accountId), "labels.json")
            if (!labelsFile.exists()) {
                Log.d("IconRestore", "$accountId labels.json not found at ${labelsFile.absolutePath}")
                emptyMap()
            } else {
                val text = labelsFile.readText()
                val map = json.decodeFromString<Map<String, String>>(text)
                Log.d("IconRestore", "$accountId labels.json loaded, ${map.size} entries")
                map
            }
        } catch (e: Exception) {
            Log.e("IconRestore", "$accountId labels.json parse failed", e)
            emptyMap()
        }
    }

    /** 优先用 labels.json 的名称，其次 PackageManager，最后包名 */
    private fun resolveAppLabel(labelMap: Map<String, String>, userId: Int, packageName: String): String {
        labelMap["${userId}-${packageName}"]?.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(packageName, 0)
            packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: packageName
        } catch (e: Exception) {
            packageName
        }
    }

    suspend fun restoreFromCloudSnapshots(group: ResticBackupGroup): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
                val password = resolveResticPassword(cloudEntity) ?: return@withContext false   // 改

                val sortedBackups = group.backups.sortedBy { backup ->
                    when (backup.dataType) {
                        DataType.PACKAGE_APK -> 0
                        DataType.PACKAGE_USER -> 1
                        DataType.PACKAGE_USER_DE -> 2
                        DataType.PACKAGE_DATA -> 3
                        DataType.PACKAGE_OBB -> 4
                        DataType.PACKAGE_MEDIA -> 5
                        DataType.PACKAGE_CONFIG -> 6
                        else -> 7
                    }
                }

                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = 0,
                    totalDataTypes = sortedBackups.size
                )

                val progressCallback = object : ResticRepository.ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        val progress = if (bytesTotal > 0) bytesWritten.toFloat() / bytesTotal else 0f
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val speedStr = if (timeDiff > 0 && bytesWritten > lastBytes) {
                            ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                        } else "0 B/s"
                        lastTime = currentTime
                        lastBytes = bytesWritten
                        _resticProgress.value = _resticProgress.value.copy(
                            filesFinished = filesFinished,
                            filesTotal = filesTotal,
                            filesSkipped = filesSkipped,
                            bytesWritten = bytesWritten,
                            bytesTotal = bytesTotal,
                            bytesSkipped = bytesSkipped,
                            percentage = progress,
                            speed = speedStr
                        )
                    }

                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {}
                }

                sortedBackups.forEachIndexed { index, backup ->
                    _resticProgress.value = _resticProgress.value.copy(
                        currentDataTypeIndex = index,
                        totalDataTypes = sortedBackups.size
                    )

                    val targetPath = "${context.localBackupSaveDir()}/restore/"
                    val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
                    val snapshotSubPath = "$backupBaseDir/apps/${backup.packageName}/user_${backup.userId}"
                    val includePath = when (backup.dataType) {
                        DataType.PACKAGE_CONFIG -> "package_restore_config.json"
                        else -> "${backup.dataType.type}.tar"
                    }
                    val fullTargetPath = "${targetPath}apps/${backup.packageName}/user_${backup.userId}/"

                    val success = when (cloudEntity.type) {              // 改：按类型分派
                        CloudType.FTP -> resticRepoFtp.restoreSnapshotFromFtp(
                            cloudEntity = cloudEntity,
                            password = password,
                            snapshotId = backup.snapshotId,
                            targetPath = fullTargetPath,
                            snapshotSubPath = snapshotSubPath,
                            includePath = includePath,
                            progressCallback = progressCallback
                        )
                        CloudType.WEBDAV -> resticRepoWebdav.restoreSnapshotFromWebdav(   // 新增
                            cloudEntity = cloudEntity,
                            password = password,
                            snapshotId = backup.snapshotId,
                            targetPath = fullTargetPath,
                            snapshotSubPath = snapshotSubPath,
                            includePath = includePath,
                            progressCallback = progressCallback
                        )
                        CloudType.SFTP -> resticRepoSftp.restoreSnapshotFromSftp(
                            cloudEntity = cloudEntity,
                            password = password,
                            snapshotId = backup.snapshotId,
                            targetPath = fullTargetPath,
                            snapshotSubPath = snapshotSubPath,
                            includePath = includePath,
                            progressCallback = progressCallback
                        )
                        else -> resticRepoCos.restoreSnapshotFromCos(
                            cloudEntity = cloudEntity,
                            password = password,
                            snapshotId = backup.snapshotId,
                            targetPath = fullTargetPath,
                            snapshotSubPath = snapshotSubPath,
                            includePath = includePath,
                            progressCallback = progressCallback
                        )
                    }

                    if (!success) {
                        Log.e("CloudRestore", "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                        _resticProgress.value = ResticProgressState()
                        return@withContext false
                    }
                }
                _resticProgress.value = ResticProgressState(isCompleted = true)
                true
            } catch (e: Exception) {
                Log.e("CloudRestore", "云端恢复异常: ${e.message}", e)
                false
            }
        }
    }

    suspend fun deleteCloudSnapshots(group: ResticBackupGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
            val password = resolveResticPassword(cloudEntity) ?: return@withContext false   // 改

            val sortedBackups = group.backups.sortedBy { backup ->
                when (backup.dataType) {
                    DataType.PACKAGE_APK -> 0
                    DataType.PACKAGE_USER -> 1
                    DataType.PACKAGE_USER_DE -> 2
                    DataType.PACKAGE_DATA -> 3
                    DataType.PACKAGE_OBB -> 4
                    DataType.PACKAGE_MEDIA -> 5
                    DataType.PACKAGE_CONFIG -> 6
                    else -> 7
                }
            }

            val totalSteps = sortedBackups.size + 1
            _resticProgress.value = ResticProgressState(
                totalDataTypes = totalSteps,
                currentDataTypeIndex = 0,
                isDeleting = true
            )

            sortedBackups.forEachIndexed { index, backup ->
                Log.d("CloudRestore", "删除第 ${index + 1}/${sortedBackups.size} 个快照: ${backup.dataType.type}")
                _resticProgress.value = _resticProgress.value.copy(currentDataTypeIndex = index)

                val success = when (cloudEntity.type) {                 // 改：按类型分派
                    CloudType.FTP -> resticRepoFtp.forgetSnapshotFromFtp(
                        cloudEntity = cloudEntity, password = password, snapshotId = backup.snapshotId
                    )
                    CloudType.WEBDAV -> resticRepoWebdav.forgetSnapshotFromWebdav(   // 新增
                        cloudEntity = cloudEntity, password = password, snapshotId = backup.snapshotId
                    )
                    CloudType.SFTP -> resticRepoSftp.forgetSnapshotFromSftp(
                        cloudEntity = cloudEntity, password = password, snapshotId = backup.snapshotId
                    )
                    else -> resticRepoCos.forgetSnapshotFromCos(
                        cloudEntity = cloudEntity, password = password, snapshotId = backup.snapshotId
                    )
                }

                if (!success) {
                    _resticProgress.value = ResticProgressState()
                    return@withContext false
                }
            }

            Log.d("CloudRestore", "执行 prune 清理 (步骤 ${totalSteps}/${totalSteps})")
            _resticProgress.value = _resticProgress.value.copy(currentDataTypeIndex = sortedBackups.size)

            val pruneSuccess = when (cloudEntity.type) {                // 改：按类型分派
                CloudType.FTP -> resticRepoFtp.pruneFtpRepository(cloudEntity, password)
                CloudType.WEBDAV -> resticRepoWebdav.pruneWebdavRepository(cloudEntity, password)   // 新增
                CloudType.SFTP -> resticRepoSftp.pruneSftpRepository(cloudEntity, password)
                else -> resticRepoCos.pruneCosRepository(cloudEntity, password)
            }

            _resticProgress.value = ResticProgressState()
            pruneSuccess
        } catch (e: Exception) {
            Log.e("CloudRestore", "删除快照异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d("CloudRestore", "=== 开始刷新本地数据库 ===")
        try {
            // 参考本地恢复：使用固定的restore目录进行清理
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            Log.d("CloudRestore", "清理旧的恢复记录: $restoreDir")
            appsDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)

            val restoreDirFile = File(restoreDir)
            Log.d("CloudRestore", "检查恢复目录是否存在: ${restoreDirFile.exists()}")
            if (restoreDirFile.exists()) {
                val appsDir = File(restoreDirFile, "apps")
                Log.d("CloudRestore", "检查apps目录是否存在: ${appsDir.exists()}, 路径: ${appsDir.path}")
                if (appsDir.exists()) {
                    Log.d("CloudRestore", "开始扫描apps目录")
                    scanAppsDirectory(appsDir)
                    Log.d("CloudRestore", "apps目录扫描完成")
                } else {
                    Log.w("CloudRestore", "apps目录不存在: ${appsDir.path}")
                }
            } else {
                Log.w("CloudRestore", "恢复目录不存在: $restoreDir")
            }
            Log.d("CloudRestore", "=== 本地数据库刷新完成 ===")
        } catch (e: Exception) {
            Log.e("CloudRestore", "数据库刷新失败", e)
        }
    }

    private suspend fun scanAppsDirectory(appsDir: File) {
        Log.d("CloudRestore", "=== 开始扫描apps目录 ===")
        Log.d("CloudRestore", "apps目录路径: ${appsDir.path}")
        Log.d("CloudRestore", "apps目录是否存在: ${appsDir.exists()}")

        val packageDirs = appsDir.listFiles { file -> file.isDirectory }
        Log.d("CloudRestore", "找到的包目录数量: ${packageDirs?.size ?: 0}")

        packageDirs?.forEach { packageDir ->
            Log.d("CloudRestore", "处理包目录: ${packageDir.name}")
            val userDirs = packageDir.listFiles { file -> file.isDirectory }
            Log.d("CloudRestore", "包 ${packageDir.name} 中的用户目录数量: ${userDirs?.size ?: 0}")

            userDirs?.forEach { userDir ->
                Log.d("CloudRestore", "处理用户目录: ${userDir.name}")
                val configFile = File(userDir, "package_restore_config.json")
                Log.d("CloudRestore", "配置文件路径: ${configFile.path}")
                Log.d("CloudRestore", "配置文件是否存在: ${configFile.exists()}")

                if (configFile.exists()) {
                    try {
                        Log.d("CloudRestore", "开始读取配置文件: ${configFile.path}")
                        val entity = readPackageConfig(configFile, packageDir.name, userDir.name)

                        if (entity != null) {
                            Log.d("CloudRestore", "配置文件解析成功: ${entity.packageName}")
                            Log.d("CloudRestore", "应用信息: packageName=${entity.packageName}, userId=${entity.indexInfo.userId}")
                            Log.d("CloudRestore", "备份信息: opType=${entity.indexInfo.opType}, backupDir=${entity.indexInfo.backupDir}")

                            Log.d("CloudRestore", "插入数据库: ${entity.packageName}")
                            appsDao.upsert(entity)

                            Log.d("CloudRestore", "激活应用: ${packageDir.name}")
                            updateDatabase(packageDir.name)

                            Log.d("CloudRestore", "应用 ${packageDir.name} 处理完成")
                        } else {
                            Log.w("CloudRestore", "配置文件解析为空: ${configFile.path}")
                        }
                    } catch (e: Exception) {
                        Log.e("CloudRestore", "处理应用配置失败: ${configFile.path}", e)
                    }
                } else {
                    Log.w("CloudRestore", "配置文件不存在: ${configFile.path}")
                }
            }
        }

        Log.d("CloudRestore", "=== apps目录扫描完成 ===")
    }

    private suspend fun readPackageConfig(configFile: File, packageName: String, userDirName: String): PackageEntity? {
        Log.d("CloudRestore", "读取配置文件: ${configFile.path}")
        return try {
            val entity = rootService.readJson<PackageEntity>(configFile.path)
            Log.d("CloudRestore", "配置文件原始数据: packageName=${entity?.packageName}, userId=${entity?.indexInfo?.userId}")

            entity?.copy(
                id = 0,
                indexInfo = entity.indexInfo.copy(
                    opType = OpType.RESTORE,
                    packageName = packageName,
                    userId = userDirName.split("_").lastOrNull()?.toIntOrNull() ?: 0,
                    cloud = "",
                    backupDir = "${context.localBackupSaveDir()}/restore/"
                ),
                extraInfo = entity.extraInfo.copy(activated = false)
            )
        } catch (e: Exception) {
            Log.e("CloudRestore", "读取配置文件失败: ${configFile.path}", e)
            null
        }
    }

    suspend fun calculateSizesForActivatedApps() {
        try {
            Log.d("CloudRestore", "=== 开始计算激活应用的大小 ===")
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            // 修改：只查询激活的应用，与第二阶段保持一致
            val activatedApps = appsDao.queryActivated(OpType.RESTORE, "", backupDir)

            Log.d("CloudRestore", "找到 ${activatedApps.size} 个已激活应用")
            activatedApps.forEach { app ->
                Log.d("CloudRestore", "计算应用大小: ${app.packageName}")
                appsRepo.calculateLocalAppArchiveSize(app)
            }

            Log.d("CloudRestore", "=== 激活应用大小计算完成 ===")
        } catch (e: Exception) {
            Log.e("CloudRestore", "计算应用大小失败", e)
        }
    }

    private suspend fun updateDatabase(packageName: String) {
        Log.d("CloudRestore", "=== 开始激活应用: $packageName ===")
        try {
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            Log.d("CloudRestore", "查询参数: opType=RESTORE, cloud=, backupDir=$backupDir")

            // 查询该包名的所有应用记录
            val existingApps = appsDao.queryPackages(OpType.RESTORE, "", backupDir)
                .filter { it.packageName == packageName }

            Log.d("CloudRestore", "查询到的应用记录数: ${existingApps.size}")
            existingApps.forEach { app ->
                Log.d("CloudRestore", "应用记录: ${app.packageName}, backupDir: ${app.indexInfo.backupDir}, activated: ${app.extraInfo.activated}, id: ${app.id}")
            }

            if (existingApps.isNotEmpty()) {
                Log.d("CloudRestore", "开始激活应用: $packageName, 找到 ${existingApps.size} 个记录")
                existingApps.forEach { app ->
                    Log.d("CloudRestore", "激活应用ID: ${app.id}")
                    appsDao.activateById(app.id, true)
                    Log.d("CloudRestore", "应用已激活: $packageName (ID: ${app.id})")
                }
            } else {
                Log.w("CloudRestore", "未找到应用记录: $packageName")

                // 添加调试查询
                val allApps = appsDao.queryPackages(OpType.RESTORE, "", backupDir)
                Log.d("CloudRestore", "数据库中所有相关应用 (${allApps.size} 个):")
                allApps.forEach { app ->
                    Log.d("CloudRestore", "- ${app.packageName}, backupDir: ${app.indexInfo.backupDir}, activated: ${app.extraInfo.activated}")
                }
            }

            Log.d("CloudRestore", "=== 应用激活完成: $packageName ===")
        } catch (e: Exception) {
            Log.e("CloudRestore", "激活应用失败: ${e.message}", e)
        }
    }

    private fun Long.formatSpeed(): String {
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${this / 1024} KiB/s"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB/s"
            else -> "${this / (1024 * 1024 * 1024)} GiB/s"
        }
    }
}

sealed interface CloudRestoreUiState {
    data object Loading : CloudRestoreUiState
    data class Success(val groups: List<ResticBackupGroup>) : CloudRestoreUiState
    data class Error(val message: String) : CloudRestoreUiState
}