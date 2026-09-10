package com.xayah.feature.main.restore

import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.DataType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticRestoreQueueItem
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticShared
import com.xayah.core.util.DateUtil
import com.xayah.feature.main.restore.ResticBackupGroup
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xayah.core.datastore.readLoadedIconMD5
import com.xayah.core.datastore.saveLoadedIconMD5
import com.xayah.core.util.iconDir
import com.xayah.core.util.filesDir
import com.xayah.core.util.PathUtil
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.util.command.Tar
import com.xayah.core.model.CompressionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import java.io.File

@HiltViewModel
class ResticRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val shared: ResticShared,
    private val appsRepo: AppsRepo,
    private val rootService: RemoteRootService,  // 添加
    private val appsDao: PackageDao
) : ViewModel() {

    companion object {
        private const val TAG = "ResticRestore"
        const val RESTORE_QUEUE_FILE = "restic_restore_queue.json"
    }

    // 速度跟踪变量 - 添加到这里
    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    private var hasLoaded = false
    private val _uiState = MutableStateFlow<ResticRestoreUiState>(ResticRestoreUiState.Loading)
    val uiState: StateFlow<ResticRestoreUiState> = _uiState.asStateFlow()

    // 图标版本信号：图标解压完成后自增，触发列表项 PackageIconImage 重新取图
    private val _iconVersion = MutableStateFlow(0)
    val iconVersion: StateFlow<Int> = _iconVersion.asStateFlow()

    // 添加进度状态跟踪
    private val _resticProgress = MutableStateFlow<ResticProgressState>(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    private val startTime = System.currentTimeMillis()

    /**
     * 批量恢复准备（本地 restic，方案乙）：
     * 1) 逐包只解出 CONFIG 到 /restore/apps/{pkg}/user_{userId}/；
     * 2) 某个包 config 解出失败/校验失败 → 只跳过该包（不入队、不激活），不影响其余包；
     * 3) 用"成功解出 config 的包"写队列 + refreshLocalDatabase 激活 + 计算大小；
     * 4) 仅当所有包都失败时才返回 false。
     */
    suspend fun prepareBatchRestore(groups: List<ResticBackupGroup>): Boolean = withContext(Dispatchers.IO) {
        try {
            if (groups.isEmpty()) {
                Log.w(TAG, "prepareBatchRestore: 选中 groups 为空")
                return@withContext false
            }
            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                Log.e(TAG, "prepareBatchRestore: restic 未配置（repoPath/password 为空）")
                return@withContext false
            }

            val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
            val targetBasePath = "${context.localBackupSaveDir()}/restore/"

            //新增：本次准备前先清空磁盘上的 restore/apps，避免上次退回列表后残留的 config 被 refreshLocalDatabase 误扫导致累加
            val appsDirToClear = File("${targetBasePath}apps")
            if (appsDirToClear.exists()) {
                Log.d(TAG, "prepareBatchRestore: 清空旧的中转目录 ${appsDirToClear.path}")
                runCatching { appsDirToClear.deleteRecursively() }
                    .onFailure { Log.e(TAG, "prepareBatchRestore: 清空 restore/apps 失败", it) }
            }

            val succeededGroups = mutableListOf<ResticBackupGroup>()
            val failedPackages = mutableListOf<String>()

            // 逐包解 config：失败只跳过当前包，继续下一个
            for (group in groups) {
                val configBackup = group.backups.firstOrNull { it.dataType == DataType.PACKAGE_CONFIG }
                if (configBackup == null) {
                    Log.e(TAG, "prepareBatchRestore: 包 ${group.packageName}(user_${group.userId}) 无 CONFIG 快照，跳过")
                    failedPackages.add("${group.packageName}/user_${group.userId}")
                    continue
                }

                val snapshotSubPath = "$backupBaseDir/apps/${group.packageName}/user_${group.userId}"
                val fullTargetPath = "${targetBasePath}apps/${group.packageName}/user_${group.userId}/"

                val ok = runCatching {
                    resticRepo.restoreSnapshot(
                        repoPath = repoPath,
                        password = password,
                        snapshotId = configBackup.snapshotId,
                        targetPath = fullTargetPath,
                        includePath = "package_restore_config.json",
                        snapshotSubPath = snapshotSubPath
                    )
                }.getOrElse {
                    Log.e(TAG, "prepareBatchRestore: 包 ${group.packageName} config 解出异常", it)
                    false
                }

                // 校验 config 文件确实落盘
                val configFile = File(fullTargetPath, "package_restore_config.json")
                if (!ok || !configFile.exists()) {
                    Log.e(TAG, "prepareBatchRestore: 包 ${group.packageName}(user_${group.userId}) config 解出/校验失败，跳过该包")
                    // 清理该包残留，避免脏数据被 refreshLocalDatabase 误扫
                    runCatching { File("${targetBasePath}apps/${group.packageName}/user_${group.userId}").deleteRecursively() }
                    failedPackages.add("${group.packageName}/user_${group.userId}")
                    continue
                }

                succeededGroups.add(group)
            }

            // 全部失败才算整批失败
            if (succeededGroups.isEmpty()) {
                Log.e(TAG, "prepareBatchRestore: 所有包 config 解出失败，无可恢复项")
                return@withContext false
            }

            // 仅用成功的包写队列（含 CONFIG 与重型，重型仍交给服务解出）
            val queue = succeededGroups.flatMap { it.backups }.map { backup ->
                ResticRestoreQueueItem(
                    packageName = backup.packageName,
                    userId = backup.userId,
                    dataType = backup.dataType,
                    snapshotId = backup.snapshotId,
                    accountName = ""   // 本地：空串
                )
            }
            File(context.cacheDir, RESTORE_QUEUE_FILE).writeText(Json.encodeToString(queue))

            // 刷 DB（内部会先删旧 RESTORE 记录，再扫已落盘的 config 并激活）+ 计算大小
            refreshLocalDatabase(targetBasePath)
            calculateSizesForActivatedApps()

            Log.d(TAG, "prepareBatchRestore: 准备完成，成功 ${succeededGroups.size} 个，跳过 ${failedPackages.size} 个：$failedPackages")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareBatchRestore 失败: ${e.message}", e)
            false
        }
    }

    fun loadBackedUpApps(force: Boolean = false) {
        // 守卫：非强制且已加载且当前已是 Success，直接复用，不重跑（连后台刷新都省掉）
        if (!force && hasLoaded && _uiState.value is ResticRestoreUiState.Success) {
            return
        }
        viewModelScope.launch {
            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            Log.d("ResticRestore", "读取到的 repoPath: $repoPath")
            Log.d("ResticRestore", "password: ${if (password.isNullOrEmpty()) "空" else "已设置"}")
            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                _uiState.value = ResticRestoreUiState.Error(context.getString(R.string.restore_error_restic_not_configured))
                return@launch
            }

            // ---- 阶段一：读持久缓存，命中秒开 ----
            val cachedApps = runCatching { readCachedApps() }.getOrElse { emptyList() }
            if (cachedApps.isNotEmpty()) {
                val labelMap = readLabelsMap("local")
                _uiState.value = ResticRestoreUiState.Success(buildGroupedBackups(cachedApps, labelMap))
                hasLoaded = true
                Log.d("ResticRestore", "缓存命中，秒开 ${cachedApps.size} 条")
            } else {
                _uiState.value = ResticRestoreUiState.Loading
            }

            // ---- 阶段二：后台重建，完成后静默替换 ----
            try {
                val freshApps = refreshAndListApps(repoPath, password)
                try {
                    loadLocalIconsFromRestic(repoPath, password)
                    // 图标已解压到 filesDir/icon/local/，自增版本触发列表图标重取
                    _iconVersion.value++
                } catch (e: Exception) {
                    Log.e(TAG, "加载本地图标失败: ${e.message}", e)
                }
                val labelMap = readLabelsMap("local")
                _uiState.value = ResticRestoreUiState.Success(buildGroupedBackups(freshApps, labelMap))
                hasLoaded = true
                Log.d("ResticRestore", "后台刷新完成，静默替换 ${freshApps.size} 条")
            } catch (e: Exception) {
                if (cachedApps.isEmpty()) {
                    _uiState.value = ResticRestoreUiState.Error(e.message ?: context.getString(R.string.restore_error_unknown))
                } else {
                    Log.w("ResticRestore", "后台刷新失败，保留缓存: ${e.message}")
                }
            }
        }
    }

    fun forceReload() = loadBackedUpApps(force = true)

    private fun buildGroupedBackups(
        apps: List<ResticBackupApp>,
        labelMap: Map<String, String>
    ): List<ResticBackupGroup> =
        apps.groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }
            .values
            .map { backups ->
                val first = backups.first()
                val label = resolveAppLabel(labelMap, first.userId, first.packageName)
                Log.d("ResticRestore", "label映射 ${first.userId}-${first.packageName} -> $label")
                ResticBackupGroup(
                    packageName = first.packageName,
                    userId = first.userId,
                    timestamp = first.timestamp,
                    backups = backups.sortedBy { it.dataType.type },
                    appLabel = label
                )
            }
            .sortedByDescending { it.timestamp }

    private suspend fun loadLocalIconsFromRestic(repoPath: String, password: String) = withContext(Dispatchers.IO) {
        val accountId = "local"
        Log.d("IconRestore", "local restore enter, accountId=$accountId")
        try {
            // 1. 找到最新的图标快照
            val snapshots = resticRepo.listSnapshots(repoPath, password)
            Log.d("IconRestore", "local snapshots total=${snapshots.size}")
            val iconSnapshots = snapshots.filter { it.tags.any { t -> t.startsWith("__icons__-$accountId-") } }
            Log.d("IconRestore", "local icon snapshots matched=${iconSnapshots.size} for accountId=$accountId")
            val iconSnapshot = iconSnapshots
                .maxByOrNull { it.time }
                ?: run {
                    Log.w("IconRestore", "local no icon snapshot found for accountId=$accountId, skip")
                    Log.d(TAG, "未找到本地图标快照，跳过")
                    return@withContext
                }
            val snapshotId = iconSnapshot.id
            Log.d("IconRestore", "local selected snapshotId=$snapshotId, tags=${iconSnapshot.tags}")

            // 2. 去重：snapshotId 未变则跳过
            // 去重判断：token 相等 且 目标目录存在且非空 才跳过；否则即使 token 命中也重新解压
            val loaded = context.readLoadedIconMD5(accountId)
            val iconDirForCheck = context.iconDir(accountId)
            val iconDirExists = File(iconDirForCheck).let { it.exists() && (it.listFiles()?.isNotEmpty() == true) }
            Log.d("IconRestore", "local dedup check, loaded=$loaded, current=$snapshotId, dirExists=$iconDirExists")
            if (loaded == snapshotId && iconDirExists) {
                Log.d("IconRestore", "local skip decompress, snapshotId unchanged ($snapshotId) and dir non-empty")
                Log.d(TAG, "图标快照未变化 ($snapshotId) 且目录非空，跳过解压")
                return@withContext
            }
            if (loaded == snapshotId && !iconDirExists) {
                Log.d("IconRestore", "local token matched but icon dir missing/empty, force re-decompress ($snapshotId)")
            }

            // 3. 整快照还原到临时目录
            val tmpDir = File(context.cacheDir, "icon_restore_$accountId").apply {
                deleteRecursively()
                mkdirs()
            }
            Log.d("IconRestore", "local restoring snapshot $snapshotId to ${tmpDir.absolutePath}")
            val restored = resticRepo.restoreSnapshot(
                repoPath = repoPath,
                password = password,
                snapshotId = snapshotId,
                targetPath = tmpDir.absolutePath
            )
            if (!restored) {
                Log.e("IconRestore", "local restore failed, snapshotId=$snapshotId")
                Log.e(TAG, "图标快照还原失败: $snapshotId")
                tmpDir.deleteRecursively()
                return@withContext
            }
            Log.d("IconRestore", "local restore ok, snapshotId=$snapshotId")

            // 4. 递归找到 icon.tar
            val iconTarName = "$IconRelativeDir.${CompressionType.TAR.suffix}"
            val iconTar = tmpDir.walkTopDown().firstOrNull { it.isFile && it.name == iconTarName }
            if (iconTar == null) {
                Log.e("IconRestore", "local $iconTarName not found in ${tmpDir.absolutePath}")
                Log.e(TAG, "临时目录未找到 $iconTarName")
                tmpDir.deleteRecursively()
                return@withContext
            }
            Log.d("IconRestore", "local found icon tar: ${iconTar.absolutePath}")

            // 5. 解压到账号维度目录
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

            // 6. 记录去重 token 并清理临时目录
            context.saveLoadedIconMD5(accountId, snapshotId)
            tmpDir.deleteRecursively()
            Log.d("IconRestore", "local icons decompressed to $iconDst, snapshotId=$snapshotId")
            Log.d(TAG, "本地图标已解压到 $iconDst, snapshotId=$snapshotId")
        } catch (e: Exception) {
            Log.e("IconRestore", "local icon restore failed", e)
        }
    }

    /** 只读持久缓存，不触发任何 JNI/网络；无缓存返回 emptyList */
    suspend fun readCachedApps(): List<ResticBackupApp> =
        shared.readCachedApps("local")

    /** 后台重建：走 JNI 写持久 db（原子覆盖），成功后解析返回 */
    suspend fun refreshAndListApps(repoPath: String, password: String): List<ResticBackupApp> =
        shared.refreshAppsDb(
            accountId = "local",
            repoPath = repoPath,
            password = password,
            options = emptyMap()
        )

    /** 读取 filesDir/icon/<accountId>/labels.json -> Map<"<userId>-<packageName>", label> */
    private fun readLabelsMap(accountId: String): Map<String, String> {
        return try {
            val labelsFile = File(context.iconDir(accountId), "labels.json")
            if (!labelsFile.exists()) {
                Log.d("IconRestore", "$accountId labels.json not found at ${labelsFile.absolutePath}")
                emptyMap()
            } else {
                val text = labelsFile.readText()
                val map = Json.decodeFromString<Map<String, String>>(text)
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

    suspend fun deleteLocalSnapshots(group: ResticBackupGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            val repoPath = context.readResticRepoPath() ?: return@withContext false
            val password = context.readResticPassword() ?: return@withContext false

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
                Log.d(TAG, "删除第 ${index + 1}/${sortedBackups.size} 个快照: ${backup.dataType.type}")

                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index
                )

                val success = resticRepo.forgetSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = backup.snapshotId
                )

                if (!success) {
                    _resticProgress.value = ResticProgressState()
                    return@withContext false
                }
            }

            Log.d(TAG, "执行 prune 清理 (步骤 ${totalSteps}/${totalSteps})")
            _resticProgress.value = _resticProgress.value.copy(
                currentDataTypeIndex = sortedBackups.size
            )

            val pruneSuccess = resticRepo.pruneRepository(repoPath, password)
            _resticProgress.value = ResticProgressState()

            pruneSuccess
        } catch (e: Exception) {
            Log.e(TAG, "删除本地快照异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d("ResticRestore", "刷新本地数据库: $backupDir")
        try {
            // 删除所有旧的恢复记录
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            appsDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)

            val restoreDirFile = File(backupDir)
            if (restoreDirFile.exists()) {
                Log.d("ResticRestore", "开始扫描恢复目录: $backupDir")

                val appsDir = File(restoreDirFile, "apps")
                if (appsDir.exists()) {
                    scanAppsDirectory(appsDir)
                } else {
                    Log.w("ResticRestore", "apps 目录不存在: ${appsDir.path}")
                }
            } else {
                Log.w("ResticRestore", "恢复目录不存在: $backupDir")
            }
            Log.d("ResticRestore", "数据库刷新完成")
        } catch (e: Exception) {
            Log.e("ResticRestore", "数据库刷新失败: ${e.message}", e)
        }
    }

    private suspend fun scanAppsDirectory(appsDir: File) {
        val packageDirs = appsDir.listFiles { file -> file.isDirectory }
        packageDirs?.forEach { packageDir ->
            val userDirs = packageDir.listFiles { file -> file.isDirectory }
            userDirs?.forEach { userDir ->
                val configFile = File(userDir, "package_restore_config.json")
                if (configFile.exists()) {
                    try {
                        // 读取配置文件并更新数据库
                        val packageEntity = readPackageConfig(configFile, packageDir.name, userDir.name)
                        if (packageEntity != null) {
                            appsDao.upsert(packageEntity)
                            Log.d("ResticRestore", "应用已插入数据库: ${packageDir.name}")
                            updateDatabase(packageDir.name)
                            Log.d("ResticRestore", "发现恢复的应用: ${packageDir.name}")
                        }
                    } catch (e: Exception) {
                        Log.e("ResticRestore", "处理应用配置失败: ${configFile.path}", e)
                    }
                }
            }
        }
    }

    private suspend fun readPackageConfig(configFile: File, packageName: String, userDirName: String): PackageEntity? {
        return try {
            val entity = rootService.readJson<PackageEntity>(configFile.path)
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
            Log.e("ResticRestore", "读取配置文件失败: ${configFile.path}", e)
            null
        }
    }

    private suspend fun updateDatabase(packageName: String) {
        Log.d("ResticRestore", "激活应用: $packageName")
        try {
            // 查询该包名的所有应用记录
            val existingApps = appsDao.queryPackages(OpType.RESTORE, "", "${context.localBackupSaveDir()}/restore/")
                .filter { it.packageName == packageName }

            Log.d("ResticRestore", "查询到的应用记录数: ${existingApps.size}")
            existingApps.forEach { app ->
                Log.d("ResticRestore", "应用记录: ${app.packageName}, backupDir: ${app.indexInfo.backupDir}, activated: ${app.extraInfo.activated}")
            }

            if (existingApps.isNotEmpty()) {
                Log.d("ResticRestore", "激活应用: $packageName, 找到 ${existingApps.size} 个记录")
                existingApps.forEach { app ->
                    appsDao.activateById(app.id, true)
                    Log.d("ResticRestore", "应用已激活: $packageName (ID: ${app.id})")
                }
            } else {
                Log.w("ResticRestore", "未找到应用记录: $packageName")

                // 添加调试查询
                val allApps = appsDao.queryPackages(OpType.RESTORE, "", "${context.localBackupSaveDir()}/restore/")
                Log.d("ResticRestore", "数据库中所有相关应用:")
                allApps.forEach { app ->
                    Log.d("ResticRestore", "- ${app.packageName}, backupDir: ${app.indexInfo.backupDir}")
                }
            }
        } catch (e: Exception) {
            Log.e("ResticRestore", "激活应用失败: ${e.message}", e)
        }
    }
    // 添加恢复方法
    suspend fun restoreFromResticSnapshots(group: ResticBackupGroup): Boolean {
        Log.d("ResticRestore", "开始快照恢复流程，包名: ${group.packageName}")
        return try {
            Log.d("ResticRestore", "读取 Restic 配置")
            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            Log.d("ResticRestore", "仓库路径: $repoPath")

            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                Log.e("ResticRestore", "Restic 配置不完整")
                _uiState.value = ResticRestoreUiState.Error("Restic not configured")
                return false
            }

            // 使用用户配置的备份目录 + /restore/
            Log.d("ResticRestore", "读取用户备份目录配置")
            val backupBaseDir = readBackupDirectory()
            val targetBasePath = "$backupBaseDir/restore/"
            Log.d("ResticRestore", "恢复目标路径: $targetBasePath")

            // 按正确顺序排序：APK优先
            Log.d("ResticRestore", "排序数据类型，共 ${group.backups.size} 个备份项")
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
            Log.d("ResticRestore", "排序后的数据类型: ${sortedBackups.map { it.dataType.type }}")

            // 依次恢复每个数据类型
            Log.d("ResticRestore", "开始逐个恢复数据类型")
            sortedBackups.forEachIndexed { index, backup ->
                val userBackupDir = context.localBackupSaveDir()
                Log.d("ResticRestore", "恢复第 ${index + 1}/${sortedBackups.size} 个数据类型: ${backup.dataType.type}")
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index,
                    totalDataTypes = sortedBackups.size
                )

                // 为当前 backup 创建专用的进度回调
                val progressCallback = object : ResticRepository.ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long,
                        filesTotal: Long,
                        bytesWritten: Long,
                        bytesTotal: Long,
                        filesSkipped: Long,
                        bytesSkipped: Long
                    ) {
                        // 恢复进度处理
                        val progress = if (bytesTotal > 0) {
                            bytesWritten.toFloat() / bytesTotal
                        } else 0f

                        // 瞬时速度计算
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val speed = if (timeDiff > 0 && bytesWritten > lastBytes) {
                            ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                        } else "0 B/s"

                        lastTime = currentTime
                        lastBytes = bytesWritten

                        Log.d("ResticRestore", "进度更新: $progress, 速度: $speed, 文件: $filesFinished/$filesTotal")
                        _resticProgress.value = ResticProgressState(
                            filesFinished = filesFinished,
                            filesTotal = filesTotal,
                            filesSkipped = filesSkipped,
                            bytesWritten = bytesWritten,
                            bytesTotal = bytesTotal,
                            bytesSkipped = bytesSkipped,
                            percentage = progress,
                            speed = speed,
                            currentDataTypeIndex = index,
                            totalDataTypes = sortedBackups.size
                        )
                    }

                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        // 原有方法体保持不变；restore 相关 VM 里 onBackupProgress 备份时不触发，可保留空/日志实现
                    }
                }

                val targetPath = "${context.localBackupSaveDir()}/restore/"
                Log.d("ResticRestore", "恢复到用户备份目录: $targetPath")
                val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
                val snapshotSubPath = "$backupBaseDir/apps/${backup.packageName}/user_${backup.userId}"
                val includePath = when (backup.dataType) {
                    DataType.PACKAGE_CONFIG -> "package_restore_config.json"
                    else -> "${backup.dataType.type}.tar"
                }
                val fullTargetPath = "${targetPath}apps/${backup.packageName}/user_${backup.userId}/"
                Log.d("ResticRestore", "恢复 ${backup.dataType.type} 到目标: $targetPath")
                Log.d("ResticRestore", "快照子路径: $snapshotSubPath")
                Log.d("ResticRestore", "包含文件: $includePath")
                val success = resticRepo.restoreSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = backup.snapshotId,
                    targetPath = fullTargetPath,
                    includePath = includePath,
                    snapshotSubPath = snapshotSubPath,
                    progressCallback = progressCallback
                )

                // 将检查移到这里
                if (!success) {
                    Log.e("ResticRestore", "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                    _resticProgress.value = ResticProgressState()
                    return false
                }
                Log.d("ResticRestore", "恢复成功: ${backup.dataType.type}")
            }

            Log.d("ResticRestore", "所有数据类型恢复完成")
            _resticProgress.value = ResticProgressState(isCompleted = true)
            true  // 成功时返回 true
        } catch (e: Exception) {
            Log.e("ResticRestore", "快照恢复异常: ${e.message}", e)
            _uiState.value = ResticRestoreUiState.Error(e.message ?: "Unknown error")
            false  // 失败时返回 false
        }
    }

    suspend fun calculateSizesForActivatedApps() {
        try {
            Log.d("ResticRestore", "=== 开始计算激活应用的大小 ===")
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            val activatedApps = appsDao.queryActivated(OpType.RESTORE, "", backupDir)  // 只查询激活

            Log.d("ResticRestore", "找到 ${activatedApps.size} 个已激活应用")
            activatedApps.forEach { app ->
                Log.d("ResticRestore", "计算应用大小: ${app.packageName}")
                appsRepo.calculateLocalAppArchiveSize(app)
            }
        } catch (e: Exception) {
            Log.e("ResticRestore", "计算应用大小失败", e)
        }
    }

    suspend fun readBackupDirectory(): String {
        Log.d("ResticRestore", "从 DataStore 读取备份目录配置")
        val backupDir = context.localBackupSaveDir()
        Log.d("ResticRestore", "读取到的备份目录: $backupDir")
        return backupDir ?: throw Exception(context.getString(R.string.restore_error_backup_dir_not_configured))
    }

    private fun Long.formatSpeed(): String {
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${this / 1024} KiB/s"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB/s"
            else -> "${this / (1024 * 1024 * 1024)} GiB/s"
        }
    }

    private fun Long.formatTime(): String {
        val minutes = this / 60
        val seconds = this % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

// 修改 UI 状态
sealed interface ResticRestoreUiState {
    object Loading : ResticRestoreUiState
    data class Success(val groups: List<ResticBackupGroup>) : ResticRestoreUiState
    data class Error(val message: String) : ResticRestoreUiState
}