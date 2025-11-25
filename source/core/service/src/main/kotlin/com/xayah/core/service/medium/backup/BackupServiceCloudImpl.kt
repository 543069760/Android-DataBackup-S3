package com.xayah.core.service.medium.backup

import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.database.dao.UploadIdDao
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.S3ClientImpl
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
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
            taskType = TaskType.MEDIA,
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
        mRemoteFilesDir = mPathUtil.getCloudRemoteFilesDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)

        log { "Trying to create: $mRemoteFilesDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteFilesDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteFileDir(archivesRelativeDir: String) = "${mRemoteFilesDir}/${archivesRelativeDir}"

    override suspend fun onFileDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteFileDir(archivesRelativeDir))
    }

    override suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String) {
        val remoteFileDir = getRemoteFileDir(m.archivesRelativeDir)

        val result = mMediumBackupUtil.backupMedia(
            m = m,
            t = t,
            r = r,
            dstDir = dstDir,
            isCanceled = { isCanceled() }
        )

        // 检查备份结果,如果失败则立即返回
        if (!result.isSuccess) {
            log { "Backup failed or canceled, skipping upload and updates" }
            return
        }

        if (t.mediaInfo.state != OperationState.SKIP) {
            mMediumBackupUtil.upload(
                client = mClient,
                m = m,
                t = t,
                srcDir = dstDir,
                dstDir = remoteFileDir,
                isCanceled = { isCanceled() }
            )
        }
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = getRemoteFileDir(archivesRelativeDir),
            onUploading = { _, _ -> },
            isCanceled = { isCanceled() }
        )
    }

    override suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {
        val remoteFileDir = getRemoteFileDir(archivesRelativeDir)  // 使用 getRemoteFileDir 而不是 getRemoteAppDir
        log { "Cleaning up failed backup at: $remoteFileDir" }

        runCatching {
            // 1. 删除已完成上传的对象(使用现有的 deleteRecursively)
            mClient.deleteRecursively(remoteFileDir)

            // 2. 清理未完成的分块上传
            val allUploadIds = mUploadIdDao.getAll()
            allUploadIds.forEach { uploadIdEntity ->
                if (uploadIdEntity.key.startsWith(remoteFileDir)) {
                    runCatching {
                        // 需要直接访问 S3ClientImpl 的方法
                        if (mClient is S3ClientImpl) {
                            // 调用 S3 API 中止分块上传
                            (mClient as S3ClientImpl).abortMultipartUpload(
                                uploadIdEntity.bucket,
                                uploadIdEntity.key,
                                uploadIdEntity.uploadId
                            )
                        }
                        mUploadIdDao.deleteById(uploadIdEntity.id)
                    }.onFailure { e ->
                        log { "Failed to abort upload ${uploadIdEntity.uploadId}: ${e.message}" }
                    }
                }
            }
        }.onSuccess {
            log { "Successfully cleaned up: $remoteFileDir" }
        }.onFailure { e ->
            log { "Failed to cleanup: ${e.message}" }
        }
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        log { "Cleaning up incomplete cloud file backup from index: $currentIndex" }

        val timestamp = mBackupTimestamp

        // 1. 删除远程文件并标记/删除数据库记录 - 只处理未完成的媒体(index >= currentIndex)
        mMediaEntities.forEachIndexed { index, media ->
            if (index >= currentIndex && media.mediaEntity.indexInfo.backupTimestamp == timestamp) {
                val m = media.mediaEntity
                val remoteFileDir = getRemoteFileDir(m.archivesRelativeDir)
                log { "Cleaning up incomplete backup at: $remoteFileDir (index: $index)" }

                // 删除远程文件
                runCatching {
                    mClient.deleteRecursively(remoteFileDir)
                }.onSuccess {
                    log { "Successfully cleaned up: $remoteFileDir" }
                }.onFailure { e ->
                    log { "Failed to cleanup: ${e.message}" }
                }

                // 标记这个特定的媒体为已取消
                log { "Marking media ${m.name} as canceled" }
                runCatching {
                    mMediaDao.markAsCanceledByTimestamp(timestamp, m.name)
                }.onSuccess {
                    log { "Successfully marked media as canceled" }
                }.onFailure { e ->
                    log { "Failed to mark media as canceled: ${e.message}" }
                }

                // 删除这个特定媒体的数据库记录(仅删除 RESTORE 类型)
                log { "Deleting canceled media ${m.name} from database (OpType.RESTORE only)" }
                runCatching {
                    mMediaDao.deleteCanceledByTimestamp(timestamp, OpType.RESTORE, m.name)
                }.onSuccess {
                    log { "Successfully deleted canceled media from database" }
                }.onFailure { e ->
                    log { "Failed to delete canceled media: ${e.message}" }
                }
            }
        }

        // 2. 清理相关的 uploadId
        log { "Cleaning up uploadId records for timestamp: $timestamp" }
        runCatching {
            val allUploadIds = mUploadIdDao.getAll()
            allUploadIds.forEach { uploadIdEntity ->
                // 检查 uploadId 是否属于本次备份
                val belongsToThisBackup = mMediaEntities.any { media ->
                    val remoteFileDir = getRemoteFileDir(media.mediaEntity.archivesRelativeDir)
                    uploadIdEntity.key.startsWith(remoteFileDir)
                }

                if (belongsToThisBackup) {
                    log { "Aborting multipart upload: ${uploadIdEntity.uploadId} for key: ${uploadIdEntity.key}" }
                    runCatching {
                        if (mClient is S3ClientImpl) {
                            (mClient as S3ClientImpl).abortMultipartUpload(
                                bucket = uploadIdEntity.bucket,
                                key = uploadIdEntity.key,
                                uploadId = uploadIdEntity.uploadId
                            )
                        }
                        mUploadIdDao.deleteById(uploadIdEntity.id)
                    }.onFailure { e ->
                        log { "Failed to abort upload ${uploadIdEntity.uploadId}: ${e.message}" }
                    }
                }
            }
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
                    delay(500)
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
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }
    override val mFilesDir by lazy { mPathUtil.getCloudTmpFilesDir() }
    override val mConfigsDir by lazy { mPathUtil.getCloudTmpConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    @Inject
    lateinit var mUploadIdDao: UploadIdDao

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteFilesDir: String
    private lateinit var mRemoteConfigsDir: String
}