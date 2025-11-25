package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
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
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.service.model.NecessaryInfo
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractBackupService : AbstractPackagesService() {
    // 新增:存储本次备份的时间戳
    protected var mBackupTimestamp: Long = 0L

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
        mBackupTimestamp = DateUtil.getTimestamp()  // 使用类变量
        val packages = mPackageRepo.queryActivated(OpType.BACKUP)

        packages.forEach { pkg ->
            pkg.indexInfo.backupTimestamp = mBackupTimestamp  // 使用类变量

            // 关键修改:确保 packageInfo 完整
            // 从系统获取最新的包信息
            val info = mRootService.getPackageInfoAsUser(
                pkg.packageName,
                0,
                pkg.userId
            )

            if (info != null) {
                // 更新 packageInfo 字段
                pkg.packageInfo.label = info.applicationInfo?.loadLabel(mContext.packageManager).toString()
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

                // 更新 extraInfo 字段
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
    protected open suspend fun clear() {}
    protected open suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {}
    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {}

    protected abstract val mPackagesBackupUtil: PackagesBackupUtil

    private lateinit var necessaryInfo: NecessaryInfo

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
            // 在每次迭代开始时检查取消标志
            if (isCanceled()) {
                log { "Backup canceled by user at index: $index" }
                val timestamp = mBackupTimestamp
                log { "Marking all packages with timestamp $timestamp as canceled" }
                runCatching {
                    mPackageDao.markAsCanceledByTimestamp(timestamp)
                }.onSuccess {
                    log { "Successfully marked packages as canceled" }
                }.onFailure { e ->
                    log { "Failed to mark packages as canceled: ${e.message}" }
                }
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
                    // 1. 执行所有数据类型的备份
                    backup(type = DataType.PACKAGE_APK, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER_DE, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_DATA, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_OBB, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_MEDIA, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)

                    // 2. 在所有数据类型备份完成后,检查取消标志
                    if (isCanceled()) {
                        log { "Backup canceled after data backup, skipping config save" }

                        // 立即标记数据库
                        val timestamp = mBackupTimestamp
                        log { "Marking all packages with timestamp $timestamp as canceled" }
                        runCatching {
                            mPackageDao.markAsCanceledByTimestamp(timestamp)
                        }.onSuccess {
                            log { "Successfully marked packages as canceled" }
                        }.onFailure { e ->
                            log { "Failed to mark packages as canceled: ${e.message}" }
                        }

                        pkg.update(state = OperationState.ERROR)
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    } else {
                        // 3. 只有在未取消的情况下才执行 permissions 和 ssaid 备份
                        mPackagesBackupUtil.backupPermissions(p = p)
                        mPackagesBackupUtil.backupSsaid(p = p)

                        // 4. 在保存配置前再次检查取消标志
                        if (isCanceled()) {
                            log { "Backup canceled before saving config" }
                            pkg.update(state = OperationState.ERROR)
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        } else if (pkg.isSuccess) {
                            // 5. 只有在未取消且备份成功的情况下才保存配置
                            p.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                            val id = restoreEntity?.id ?: 0
                            restoreEntity = p.copy(
                                id = id,
                                indexInfo = p.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir),
                                extraInfo = p.extraInfo.copy(activated = false)
                            )
                            val configDst = PathUtil.getPackageRestoreConfigDst(dstDir = dstDir)
                            mRootService.writeJson(data = restoreEntity, dst = configDst)
                            onConfigSaved(path = configDst, archivesRelativeDir = p.archivesRelativeDir)
                            mPackageDao.upsert(restoreEntity)
                            mPackageDao.upsert(p)
                            pkg.update(packageEntity = p)
                            mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                        } else {
                            // 备份失败,清理已上传的文件
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

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                if (mContext.readBackupItself().first()) {
                    log { "Backup itself enabled." }
                    mCommonBackupUtil.backupItself(dstDir = mRootDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onItselfSaved(path = mCommonBackupUtil.getItselfDst(mRootDir), entity = entity)
                        }
                    }
                    entity.update(progress = 1f)
                } else {
                    entity.update(progress = 1f, state = OperationState.SKIP)
                }
            }

            ProcessingInfoType.SAVE_ICONS -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.save_icons)
                )
                mPackagesBackupUtil.backupIcons(dstDir = mConfigsDir).apply {
                    entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                    if (isSuccess) {
                        onIconsSaved(path = mPackagesBackupUtil.getIconsDst(mConfigsDir), entity = entity)
                    }
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