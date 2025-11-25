package com.xayah.core.service.packages.backup

import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.database.dao.UploadIdDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.S3ClientImpl
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
internal class BackupServiceCloudImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceCloudImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun onTargetDirsCreated() {
        mCloudRepo.getClient().also { (c, e) ->
            mCloudEntity = e
            mClient = c
        }

        mRemotePath = mCloudEntity.remote
        mRemoteAppsDir = mPathUtil.getCloudRemoteAppsDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)

        log { "Trying to create: $mRemoteAppsDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteAppsDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) = "${mRemoteAppsDir}/${archivesRelativeDir}"

    override suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteAppDir(archivesRelativeDir))
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        // 在开始备份前检查取消标志
        if (isCanceled()) {
            log { "Backup canceled before processing $type" }
            return
        }

        val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)
        val result = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(p = p, t = t, r = r, dstDir = dstDir, isCanceled = { isCanceled() })
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, r = r, dataType = type, dstDir = dstDir, isCanceled = { isCanceled() })
        }

        // 压缩后再次检查取消标志
        if (isCanceled()) {
            log { "Backup canceled after compression for $type" }
            return
        }

        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            mPackagesBackupUtil.upload(
                client = mClient,
                p = p,
                t = t,
                dataType = type,
                srcDir = dstDir,
                dstDir = remoteAppDir,
                isCanceled = { isCanceled() }
            )
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = getRemoteAppDir(archivesRelativeDir),
            onUploading = { _, _ -> },
            isCanceled = { isCanceled() }
        )
    }

    override suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {
        val remoteAppDir = getRemoteAppDir(archivesRelativeDir)
        log { "Cleaning up failed backup at: $remoteAppDir" }

        runCatching {
            // 使用 deleteRecursively 删除目录下所有对象
            mClient.deleteRecursively(remoteAppDir)

            // 清理未完成的分块上传
            log { "Cleaning up incomplete multipart uploads for: $remoteAppDir" }
            val uploadIds = mUploadIdDao.getAll()
            uploadIds.forEach { uploadIdEntity ->
                if (uploadIdEntity.key.startsWith(remoteAppDir)) {
                    log { "Aborting multipart upload: ${uploadIdEntity.uploadId}" }
                    runCatching {
                        // 需要类型转换为 S3ClientImpl 才能调用 abortMultipartUpload
                        if (mClient is S3ClientImpl) {
                            (mClient as S3ClientImpl).abortMultipartUpload(
                                uploadIdEntity.bucket,
                                uploadIdEntity.key,
                                uploadIdEntity.uploadId
                            )
                        }
                        mUploadIdDao.deleteById(uploadIdEntity.id)
                    }.onFailure { e ->
                        log { "Failed to abort upload: ${e.message}" }
                    }
                }
            }
        }.onSuccess {
            log { "Successfully cleaned up: $remoteAppDir" }
        }.onFailure { e ->
            log { "Failed to cleanup: ${e.message}" }
        }
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        log { "Cleaning up incomplete backups from index: $currentIndex" }

        val timestamp = mBackupTimestamp
        log { "Using timestamp: $timestamp for cleanup" }

        // 删除未完成的包(基于状态判断)
        mPkgEntities.forEachIndexed { index, pkg ->
            val p = pkg.packageEntity
            // 只删除时间戳匹配且状态不是 DONE 的包
            if (index >= currentIndex - 1 && pkg.packageEntity.indexInfo.backupTimestamp == timestamp) {
                val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)
                log { "Cleaning up incomplete backup: ${p.packageName} at $remoteAppDir" }

                // 删除远程文件
                runCatching {
                    mClient.deleteRecursively(remoteAppDir)
                }.onSuccess {
                    log { "Successfully cleaned up: $remoteAppDir" }
                }.onFailure { e ->
                    log { "Failed to cleanup: ${e.message}" }
                }

                // 标记和删除数据库记录
                runCatching {
                    mPackageDao.markAsCanceledByTimestamp(timestamp, p.packageName, p.userId)
                    mPackageDao.deleteCanceledByTimestamp(timestamp, OpType.RESTORE, p.packageName, p.userId)
                }.onSuccess {
                    log { "Successfully deleted package from database" }
                }.onFailure { e ->
                    log { "Failed to delete package: ${e.message}" }
                }
            }
        }

        // 4. 清理相关的 uploadId
        log { "Cleaning up uploadId records for timestamp: $timestamp" }
        runCatching {
            mUploadIdDao.deleteByTimestamp(timestamp)
        }.onSuccess {
            log { "Successfully cleaned up uploadId records" }
        }.onFailure { e ->
            log { "Failed to cleanup uploadId records: ${e.message}" }
        }
    }

    override suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        var flag = true
        var progress = 0f
        var speed = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | ${(progress * 100).toInt()}%"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    entity.update(content = content)
                    delay(300)
                }
            }
        }

        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = mRemotePath,
            onUploading = { read, total ->
                progress = read.toFloat() / total
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastTime
                if (timeDiff >= 300) {
                    val bytesDiff = read - lastBytes
                    speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                    lastTime = currentTime
                    lastBytes = read
                }
            },
            isCanceled = { isCanceled() }
        ).apply {
            flag = false
            entity.update(
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = if (isSuccess) null else outString,
                content = "100%"
            )
        }
    }

    override suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        var flag = true
        var progress = 0f
        var speed = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | ${(progress * 100).toInt()}%"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    entity.update(content = content)
                    delay(500)
                }
            }
        }

        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = mRemoteConfigsDir,
            onUploading = { read, total ->
                progress = read.toFloat() / total
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastTime
                if (timeDiff >= 500) {
                    val bytesDiff = read - lastBytes
                    speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                    lastTime = currentTime
                    lastBytes = read
                }
            },
            isCanceled = { isCanceled() }
        ).apply {
            flag = false
            entity.update(
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = if (isSuccess) null else outString,
                content = "100%"
            )
        }
    }

    override suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        var flag = true
        var progress = 0f
        var speed = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | ${(progress * 100).toInt()}%"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    entity.update(content = content)
                    delay(500)
                }
            }
        }

        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = mRemoteConfigsDir,
            onUploading = { read, total ->
                progress = read.toFloat() / total
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastTime
                if (timeDiff >= 500) {
                    val bytesDiff = read - lastBytes
                    speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                    lastTime = currentTime
                    lastBytes = read
                }
            },
            isCanceled = { isCanceled() }
        ).apply {
            flag = false
            entity.update(
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = if (isSuccess) null else outString,
                content = "100%"
            )
        }
    }

    override suspend fun clear() {
        mRootService.deleteRecursively(mRootDir)
        mClient.disconnect()
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }
    override val mAppsDir by lazy { mPathUtil.getCloudTmpAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getCloudTmpConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    @Inject
    lateinit var mUploadIdDao: UploadIdDao

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
    private lateinit var mRemoteConfigsDir: String
}