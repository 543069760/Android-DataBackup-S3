package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.xayah.core.common.util.toLineString
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.CompressionType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.set
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.service.R
import com.xayah.core.service.model.NecessaryInfo
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepository.ResticProgressCallback
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readResticPassword
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
internal abstract class AbstractBackupService : AbstractPackagesService() {
    protected var mBackupTimestamp: Long = 0L

    @Inject
    lateinit var resticRepo: ResticRepository

    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_preparations),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.NECESSARY_PREPARATIONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.backup_itself),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.BACKUP_ITSELF
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.save_icons),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.SAVE_ICONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_remaining_data_processing),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    @SuppressLint("StringFormatInvalid")
    override suspend fun onInitializing() {
        // 生成本次备份的统一时间戳
        mBackupTimestamp = DateUtil.getTimestamp()

        val packages = mPackageRepo.queryActivated(OpType.BACKUP)

        packages.forEach { pkg ->
            pkg.indexInfo.backupTimestamp = mBackupTimestamp

            val info = mRootService.getPackageInfoAsUser(
                pkg.packageName,
                0,
                pkg.userId
            )

            if (info != null) {
                // label：带重试 + 坏值不覆盖。
                // loadLabel 偶发返回包名/空值时，不要用坏值冲掉上次备份写入的好值
                // （pkg 来自 queryActivated(OpType.BACKUP)，其 label 可能已是正确值）。
                resolveCleanLabel(info, pkg)?.let { pkg.packageInfo.label = it }
                pkg.packageInfo.versionName = info.versionName ?: ""
                pkg.packageInfo.versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                pkg.packageInfo.flags = info.applicationInfo?.flags ?: 0
                pkg.packageInfo.firstInstallTime = info.firstInstallTime
                pkg.packageInfo.lastUpdateTime = info.lastUpdateTime

                pkg.extraInfo.uid = info.applicationInfo?.uid ?: -1
                pkg.extraInfo.permissions = mRootService.getPermissions(packageInfo = info)
                pkg.extraInfo.enabled = info.applicationInfo?.enabled ?: false
            }

            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    /**
     * 解析一个「干净的」label：非空、非空白、且不等于包名。
     * loadLabel 偶发返回包名/空时重试一次；两次都拿不到有效值返回 null（调用方保留原值，不覆盖）。
     */
    private suspend fun resolveCleanLabel(
        info: android.content.pm.PackageInfo,
        pkg: PackageEntity
    ): String? {
        fun android.content.pm.PackageInfo.cleanLabel(): String? =
            applicationInfo?.loadLabel(mContext.packageManager)?.toString()
                ?.takeIf { it.isNotBlank() && it != pkg.packageName }

        info.cleanLabel()?.let { return it }
        // 重试：重新跨进程取一次 PackageInfo 再 loadLabel
        val retry = mRootService.getPackageInfoAsUser(pkg.packageName, 0, pkg.userId)
        return retry?.cleanLabel()
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = true
    abstract suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String)
    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun prepareRemoteIconsForMerge(): File? = null
    protected open suspend fun clear() {
        // 清理停止文件
        cleanupStopFiles()
    }
    protected open suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {}
    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        // 清理停止文件
        cleanupStopFiles()
    }

    abstract val mPackagesBackupUtil: PackagesBackupUtil

    private lateinit var necessaryInfo: NecessaryInfo

    // Restic 辅助方法：获取仓库路径
    protected suspend fun getResticRepoPath(): String {
        // 从 DataStore 读取用户配置的路径，与 ResticViewModel 保持一致
        return mContext.readResticRepoPath() ?: File(mContext.filesDir, "restic_repo").absolutePath
    }

    // Restic 辅助方法：生成密码
    protected suspend fun getResticPassword(): String {
        // 从 DataStore 读取用户配置的密码，如果没有则使用默认值
        return mContext.readResticPassword() ?: "databackup_${mBackupTimestamp}"
    }

    // Restic 辅助方法：更新数据库中的快照信息（存根）
    private suspend fun updateResticInfo(packageName: String, snapshotId: String) {
        Log.d("ResticFlow", "updateResticInfo() called - packageName: $packageName, snapshotId: $snapshotId")

        try {
            // 找到对应的 PackageEntity 并更新
            val entity = mPkgEntities.find { it.packageEntity.packageName == packageName }
            if (entity != null) {
                Log.d("ResticFlow", "Found PackageEntity for $packageName, id: ${entity.packageEntity.id}")

                val repoPath = getResticRepoPath()
                Log.d("ResticFlow", "Repo path: $repoPath")

                // 使用 DAO 的专用方法更新
                mPackageDao.updateResticInfo(
                    id = entity.packageEntity.id,
                    snapshotId = snapshotId,
                    repoPath = repoPath
                )
                Log.d("ResticFlow", "PackageEntity updated successfully for $packageName")
            } else {
                Log.w("ResticFlow", "PackageEntity not found for $packageName")
                Log.d("ResticFlow", "Available packages: ${mPkgEntities.map { it.packageEntity.packageName }}")
            }
        } catch (e: Exception) {
            Log.e("ResticFlow", "Error updating Restic info for $packageName", e)
            throw e // 重新抛出异常以便上层捕获
        }
    }


    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                necessaryInfo = NecessaryInfo(inputMethods = PreparationUtil.getInputMethods().outString.trim(), accessibilityServices = PreparationUtil.getAccessibilityServices().outString.trim())
                log { "InputMethods: ${necessaryInfo.inputMethods}." }
                log { "AccessibilityServices: ${necessaryInfo.accessibilityServices}." }

                log { "Trying to create: $mAppsDir." }
                log { "Trying to create: $mConfigsDir." }
                mRootService.mkdirs(mAppsDir)
                mRootService.mkdirs(mConfigsDir)
                val isSuccess = runCatchingOnService { onTargetDirsCreated() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }
            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.PACKAGE), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mPkgEntities.size)
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        for (index in mPkgEntities.indices) {
            if (isCanceled()) {
                log { "Backup canceled by user at index: $index" }
                break
            }

            val pkg = mPkgEntities[index]
            executeAtLeast {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    pkg.packageEntity.packageInfo.label,
                    mPkgEntities.size,
                    index
                )
                log { "Current package: ${pkg.packageEntity}" }

                killApp(killAppOption, pkg)

                pkg.update(state = OperationState.PROCESSING)
                val p = pkg.packageEntity
                val dstDir = "${mAppsDir}/${p.archivesRelativeDir}"
                var restoreEntity = mPackageDao.query(
                    p.packageName,
                    OpType.RESTORE,
                    p.userId,
                    p.indexInfo.compressionType,
                    mTaskEntity.cloud,
                    mTaskEntity.backupDir,
                    p.indexInfo.backupTimestamp
                )
                mRootService.mkdirs(dstDir)

                if (onAppDirCreated(archivesRelativeDir = p.archivesRelativeDir)) {
                    val dataTypes = listOf(
                        DataType.PACKAGE_APK,
                        DataType.PACKAGE_USER,
                        DataType.PACKAGE_USER_DE,
                        DataType.PACKAGE_DATA,
                        DataType.PACKAGE_OBB,
                        DataType.PACKAGE_MEDIA,
                    )
                    for (type in dataTypes) {
                        if (isCanceled()) {
                            log { "Backup canceled, skipping remaining data types for ${p.packageName}" }
                            break
                        }
                        backup(type = type, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    }

                    if (isCanceled()) {
                        log { "Backup canceled after data backup, skipping config save" }
                        pkg.update(state = OperationState.ERROR)
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    } else {
                        mPackagesBackupUtil.backupPermissions(p = p)
                        mPackagesBackupUtil.backupSsaid(p = p)

                        if (isCanceled()) {
                            log { "Backup canceled before saving config" }
                            pkg.update(state = OperationState.ERROR)
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        } else if (pkg.isSuccess) {
                            p.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                            val id = restoreEntity?.id ?: 0
                            restoreEntity = p.copy(
                                id = id,
                                indexInfo = p.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir, compressionType = CompressionType.TAR),
                                extraInfo = p.extraInfo.copy(activated = false)
                            )

                            val configDst = PathUtil.getPackageRestoreConfigDst(dstDir = dstDir)
                            mRootService.writeJson(data = restoreEntity, dst = configDst)
                            val configFile = File(configDst)
                            if (configFile.exists()) {
                                backup(type = DataType.PACKAGE_CONFIG, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                                log { "Config backup completed for ${p.packageName}" }
                            }
                            onConfigSaved(path = configDst, archivesRelativeDir = p.archivesRelativeDir)
                            mPackageDao.upsert(restoreEntity)
                            mPackageDao.upsert(p)
                            pkg.update(packageEntity = p)
                            mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                        } else {
                            log { "Backup failed for ${p.packageName}, cleaning up remote files..." }
                            runCatching {
                                onCleanupFailedBackup(archivesRelativeDir = p.archivesRelativeDir)
                            }.onFailure { e ->
                                log { "Failed to cleanup remote files: ${e.message}" }
                            }
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        }
                        pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
                    }
                } else {
                    pkg.update(dataType = DataType.PACKAGE_APK, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER_DE, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_DATA, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_OBB, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_MEDIA, state = OperationState.ERROR)
                    pkg.update(state = OperationState.ERROR)
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    // 添加成员变量来跟踪当前正在备份的标签
    protected var mCurrentProcessingTag: String? = null

    // 跟踪当前 rustic 备份的取消令牌 id（0L 表示无进行中的可取消备份）
    @Volatile
    protected var mCurrentBackupCancelId: Long = 0L

    // 重写 cancel 方法
    override fun cancel() {
        super.cancel()

        // 向正在运行的 rustic JNI 备份发送协作式取消信号
        val cancelId = mCurrentBackupCancelId
        Log.i("RusticCancel", "cancel() called, cancelId=$cancelId")
        if (cancelId != 0L) {
            // cancelRusticBackup 是 suspend（RemoteRootService.getService() 为 suspend），
            // 而 cancel() 非 suspend，必须在独立协程里发；用新 scope，避免被正在取消的任务一起取消。
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.i("RusticCancel", "Before cancelRusticBackup, cancelId=$cancelId")
                    mRootService.cancelRusticBackup(cancelId)
                    Log.i("RusticCancel", "After cancelRusticBackup, cancelId=$cancelId")
                } catch (e: Exception) {
                    Log.i("RusticCancel", "Failed to cancel rustic backup: ${e.message}")
                }
            }
        }

        try {
            val currentIndex = mTaskEntity.processingIndex
            if (currentIndex < mPkgEntities.size) {
                val pkg = mPkgEntities[currentIndex]
                val p = pkg.packageEntity
                val userId = "user_${p.userId}"

                // 为所有数据类型创建停止文件
                val dataTypes = listOf("apk", "user", "user_de", "data", "obb", "media", "config")

                dataTypes.forEach { dataType ->
                    val tag = "$userId-${p.packageName}-$mBackupTimestamp-$dataType"
                    val stopFile = File(mContext.cacheDir, tag)
                    stopFile.writeText(tag)
                }

                Log.i("RusticCancel", "Created stop files for all data types of ${p.packageName}")
            } else if (currentIndex > 0 && currentIndex <= mPkgEntities.size) {
                // 当前包刚完成,但可能还有清理工作
                val pkg = mPkgEntities[currentIndex - 1]
                val p = pkg.packageEntity
                val userId = "user_${p.userId}"

                // 仍然创建停止文件,以防有延迟的 Restic 进程
                val dataTypes = listOf("apk", "user", "user_de", "data", "obb", "media", "config")

                dataTypes.forEach { dataType ->
                    val tag = "$userId-${p.packageName}-$mBackupTimestamp-$dataType"
                    val stopFile = File(mContext.cacheDir, tag)
                    stopFile.writeText(tag)
                }

                Log.i("RusticCancel", "Created stop files for recently completed package ${p.packageName}")
            } else {
                Log.i("RusticCancel", "No packages to cancel")
            }
        } catch (e: Exception) {
            Log.i("RusticCancel", "Failed to create stop files: ${e.message}")
        }
    }

    protected fun cleanupStopFiles() {
        try {
            val cacheDir = mContext.cacheDir
            log { "Starting cleanup of stop files in: ${cacheDir.absolutePath}" }

            val allFiles = cacheDir.listFiles()
            log { "Total files in cache: ${allFiles?.size ?: 0}" }

            var deletedCount = 0
            allFiles?.forEach { file ->
                log { "Checking file: ${file.name}" }
                if (file.name.matches(Regex("user_\\d+-.*-\\d+-\\w+"))) {
                    file.delete()
                    log { "Deleted stop file: ${file.name}" }
                    deletedCount++
                }
            }

            log { "Cleaned up $deletedCount stop files" }
        } catch (e: Exception) {
            log { "Failed to cleanup stop files: ${e.message}" }
        }
    }

    // Restic 无状态备份方法
    // 【修改】新增 t 参数，用于把本地备份进度（速度 | 累积已上传字节）刷到对应数据类型行
    protected suspend fun backupWithRestic(
        packageName: String,
        compressedFile: File,
        dataType: DataType,
        t: TaskDetailPackageEntity
    ): Boolean {
        Log.d("ResticFlow", "backupWithRestic() ENTRY - packageName: $packageName, file: ${compressedFile.absolutePath}, type: $dataType")
        val repoPath = getResticRepoPath()
        val password = getResticPassword()

        if (!resticRepo.checkRepository(repoPath, password)) {
            log { "Restic repository not initialized, skipping backup for $packageName" }
            return false
        }

        // 进度累加：回调由 native 上传线程触发，只写入原子字段，不在该线程做阻塞 DB 写
        val bytesDoneRef = AtomicLong(0L)
        val speedRef = AtomicLong(0L)
        val polling = AtomicBoolean(true)

        // 备份是流式实时去重，无可预知总量 => 不显示百分比，只显示 速度 | 累积字节
        val progressCallback = object : ResticProgressCallback {
            override fun onRestoreProgress(
                filesFinished: Long, filesTotal: Long,
                bytesWritten: Long, bytesTotal: Long,
                filesSkipped: Long, bytesSkipped: Long
            ) {
                // 恢复进度，备份不使用
            }

            override fun onBackupProgress(
                percentDone: Float, bytesDone: Long,
                bytesTotal: Long, filesDone: Long, filesTotal: Long,
                speed: Long
            ) {
                // percentDone 恒为 0（无总量），忽略；仅记录累积字节与速度
                bytesDoneRef.set(bytesDone)
                speedRef.set(speed)
            }
        }

        // 轮询协程：每 500ms 把 速度 | 累积字节 写回 UI
        val pollingJob = with(CoroutineScope(coroutineContext)) {
            launch {
                while (polling.get()) {
                    val speed = speedRef.get()
                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val bytesText = bytesDoneRef.get().toDouble().formatSize()
                    val content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                    t.update(dataType = dataType, content = content)
                    delay(500)
                }
            }
        }

        return try {
            val filePath = compressedFile.absolutePath
            val userId = extractUserIdFromPath(filePath)
            val backupType = dataType.type

            val tag = "$userId-$packageName-$mBackupTimestamp-$backupType"
            val tags = listOf(tag)

            // 【新增】设置当前处理的标签
            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            // 新增：为本次 rustic 备份生成进程内唯一取消令牌 ID，并登记到字段供 cancel() 使用
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId

            val additionalEnv = mapOf(
                "RUSTIC_INSTANCE_LABEL" to tag
            )
            Log.d("ResticTag", "Additional env: $additionalEnv")
            log { "Starting Restic backup for $packageName with tag: $tag" }
            val result = resticRepo.backupWithResticToLocal(
                repoPath = repoPath,
                password = password,
                filePath = filePath,
                tags = tags,
                additionalEnv = additionalEnv,
                progressCallback = progressCallback,
                cancelId = cancelId
            )
            // 【新增】清除当前标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                log { "Restic backup completed successfully for $packageName" }
                // 结束时把最终累积字节定格到 UI
                val finalSpeed = speedRef.get()
                val finalSpeedText = if (finalSpeed > 0) finalSpeed.formatToStorageSizePerSecond() else ""
                val finalBytes = bytesDoneRef.get().toDouble().formatSize()
                t.update(dataType = dataType, content = if (finalSpeedText.isNotEmpty()) "$finalSpeedText | $finalBytes" else finalBytes)

                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    updateResticInfo(packageName, snapshotId)
                } else {
                    Log.e("ResticFlow", "Failed to extract snapshot ID from: ${result.second}")
                }
                true
            } else {
                val errorMsg = result.second
                log { "Restic backup failed for $packageName: $errorMsg" }
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e   // ★ 新增：取消异常直接重抛，勿吞
            // 【新增】异常时也要清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            val baseMessage = "Error during Restic backup"
            log { "$baseMessage for $packageName" }
            log { "Exception type: ${e.javaClass.simpleName}" }
            log { "Exception message: ${e.message}" }
            false
        } finally {
            // 停止轮询协程，避免泄漏
            polling.set(false)
            pollingJob.cancel()
        }
    }

    // 添加辅助方法：从路径中提取用户ID
    protected fun extractUserIdFromPath(path: String): String {
        val regex = Regex("/(user_\\d+)/")
        return regex.find(path)?.groupValues?.get(1) ?: "user_0"
    }

    // 添加辅助方法：从JSON输出中提取快照ID
    private fun extractSnapshotIdFromJson(jsonOutput: String): String? {
        return jsonOutput.lines()
            .find { it.contains("\"message_type\":\"summary\"") }
            ?.let { line ->
                Regex("\"snapshot_id\":\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                entity.update(progress = 1f, state = OperationState.SKIP)
            }
            ProcessingInfoType.SAVE_ICONS -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.save_icons)
                )
                // 先尝试拉取并解压远端最新图标目录（失败/无则为 null，退回纯本机逻辑，不阻断备份）
                val remoteDir = runCatching { prepareRemoteIconsForMerge() }.getOrNull()
                try {
                    mPackagesBackupUtil.backupIconsAndLabels(dstDir = mConfigsDir, remoteIconDir = remoteDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onIconsSaved(path = mPackagesBackupUtil.getIconsAndLabelsDst(mConfigsDir), entity = entity)
                        }
                    }
                } finally {
                    runCatching { remoteDir?.deleteRecursively() }
                }
                entity.update(progress = 1f)
            }

            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                var isSuccess = true
                val out = mutableListOf<String>()
                if (mContext.readBackupConfigs().first()) {
                    log { "Backup configs enabled." }
                    mCommonBackupUtil.backupConfigs(dstDir = mConfigsDir).also { result ->
                        if (result.isSuccess.not()) {
                            isSuccess = false
                        }
                        out.add(result.outString)
                        if (result.isSuccess) {
                            onConfigsSaved(path = mCommonBackupUtil.getConfigsDst(mConfigsDir), entity = entity)
                        }
                    }
                }
                entity.update(progress = 0.5f)

                // Restore keyboard and services.
                if (necessaryInfo.inputMethods.isNotEmpty()) {
                    PreparationUtil.setInputMethods(inputMethods = necessaryInfo.inputMethods)
                    log { "InputMethods restored: ${necessaryInfo.inputMethods}." }
                } else {
                    log { "InputMethods is empty, skip restoring." }
                }
                if (necessaryInfo.accessibilityServices.isNotEmpty()) {
                    PreparationUtil.setAccessibilityServices(accessibilityServices = necessaryInfo.accessibilityServices)
                    log { "AccessibilityServices restored: ${necessaryInfo.accessibilityServices}." }
                } else {
                    log { "AccessibilityServices is empty, skip restoring." }
                }
                if (mContext.readResetBackupList().first() && mTaskEntity.failureCount == 0) {
                    mPackageDao.clearActivated(OpType.BACKUP)
                }
                if (runCatchingOnService { clear() }.not()) {
                    isSuccess = false
                }
                entity.set(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastBackupTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.backup_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}