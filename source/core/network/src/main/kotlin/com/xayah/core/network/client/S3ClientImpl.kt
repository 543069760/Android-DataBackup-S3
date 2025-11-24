package com.xayah.core.network.client

import android.content.Context
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.content.writeToFile
import aws.smithy.kotlin.runtime.content.asByteStream
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.network.R
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.model.PickerType
import com.xayah.core.model.database.S3NetworkType
import com.xayah.core.model.database.UploadIdEntity
import com.xayah.core.database.dao.UploadIdDao
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.max
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class S3ClientImpl(
    private val entity: CloudEntity,
    private val extra: S3Extra,
    private val uploadIdDao: UploadIdDao
) : CloudClient {
    private var s3Client: S3Client? = null

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "S3ClientImpl" to msg() }
        msg()
    }

    private fun normalizeObjectKey(path: String): String {
        return path.trim('/').replace("//", "/")
    }

    companion object {
        suspend fun cleanupOrphanedUpload(
            uploadIdEntity: UploadIdEntity,
            cloudEntity: CloudEntity
        ) {
            val extra: S3Extra = GsonUtil().fromJson(cloudEntity.extra, S3Extra::class.java) ?: return

            val s3Client = S3Client {
                region = if (extra.endpoint.isNotEmpty()) {
                    extra.region.ifEmpty { "us-east-1" }
                } else {
                    extra.region
                }
                credentialsProvider = object : CredentialsProvider {
                    override suspend fun resolve(attributes: aws.smithy.kotlin.runtime.collections.Attributes): Credentials {
                        return Credentials(
                            accessKeyId = extra.accessKeyId,
                            secretAccessKey = extra.secretAccessKey
                        )
                    }
                }
                if (extra.endpoint.isNotEmpty()) {
                    val scheme = when (extra.protocol) {
                        S3Protocol.HTTP -> "http"
                        S3Protocol.HTTPS -> "https"
                    }
                    endpointUrl = Url.parse("$scheme://${extra.endpoint}")
                }
            }

            try {
                s3Client.abortMultipartUpload(
                    AbortMultipartUploadRequest {
                        bucket = uploadIdEntity.bucket
                        key = uploadIdEntity.key
                        uploadId = uploadIdEntity.uploadId
                    }
                )
            } finally {
                s3Client.close()
            }
        }
    }

    override fun connect() {
        s3Client = S3Client {
            // 如果有自定义 endpoint,region 可以是任意值
            // 否则使用用户提供的 region 或默认值
            region = if (extra.endpoint.isNotEmpty()) {
                extra.region.ifEmpty { "us-east-1" }
            } else {
                extra.region
            }
            credentialsProvider = object : CredentialsProvider {
                override suspend fun resolve(attributes: aws.smithy.kotlin.runtime.collections.Attributes): Credentials {
                    return Credentials(
                        accessKeyId = extra.accessKeyId,
                        secretAccessKey = extra.secretAccessKey
                    )
                }
            }
            if (extra.endpoint.isNotEmpty()) {
                // 根据协议配置构建endpoint URL
                val scheme = when (extra.protocol) {
                    S3Protocol.HTTP -> "http"
                    S3Protocol.HTTPS -> "https"
                }
                endpointUrl = Url.parse("$scheme://${extra.endpoint}")
            }
        }
    }

    override fun disconnect() {
        runBlocking {
            s3Client?.close()
        }
        s3Client = null
    }

    override fun mkdir(dst: String) {
        val key = normalizeObjectKey(dst) + "/"
        log { "mkdir: $key" }
        runBlocking {
            s3Client?.putObject(PutObjectRequest {
                bucket = extra.bucket
                this.key = key
                body = ByteStream.fromBytes(ByteArray(0))
            })
        }
    }

    override fun mkdirRecursively(dst: String) {
        mkdir(dst)
    }

    private fun calculatePartSize(fileSize: Long): Long {
        val maxParts = 10000L
        val minPartSize = 10L * 1024 * 1024  // 10MB

        val calculatedSize = fileSize / maxParts

        return when {
            calculatedSize < minPartSize -> minPartSize
            else -> calculatedSize
        }
    }

    override fun renameTo(
        src: String,
        dst: String,
        onProgress: ((currentPart: Int, totalParts: Int, currentFile: Int, totalFiles: Int) -> Unit)?
    ) {
        // S3 不再需要 renameTo 操作,因为备份完全基于时间戳
        log { "renameTo is deprecated for S3, skipping: $src to $dst" }
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit, isCanceled: (() -> Boolean)?) {
        runBlocking {
            val name = PathUtil.getFileName(src)
            val dstPath = normalizeObjectKey("$dst/$name")
            log { "upload: $src to $dstPath" }

            val srcFile = File(src)
            val srcFileSize = srcFile.length()
            val partSize = calculatePartSize(srcFileSize).toInt()

            val createMultipartUploadResponse = s3Client?.createMultipartUpload(
                CreateMultipartUploadRequest {
                    bucket = extra.bucket
                    key = dstPath
                }
            )

// 立即持久化 uploadId
            val uploadIdEntity = UploadIdEntity(
                uploadId = createMultipartUploadResponse?.uploadId ?: "",
                bucket = extra.bucket,
                key = dstPath,
                timestamp = System.currentTimeMillis(),
                cloudName = entity.name
            )
            val recordId = uploadIdDao.insert(uploadIdEntity)

            // 根据网络类型动态设置并发数
            val concurrency = when (extra.networkType) {
                S3NetworkType.PRIVATE -> 5  // 内网使用 5 个并发
                S3NetworkType.PUBLIC -> 3   // 公网使用 3 个并发
            }
            log { "Using concurrency: $concurrency for network type: ${extra.networkType}" }
            val channel = kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>(capacity = concurrency / 2)

            val uploadedBytes = java.util.concurrent.atomic.AtomicLong(0L)
            val completedParts = java.util.concurrent.ConcurrentHashMap<Int, CompletedPart>()

            // 生产者协程:读取文件分块
            val producer = launch {
                try {
                    srcFile.inputStream().buffered().use { file ->
                        var pn = 1
                        val partBuf = ByteArray(partSize)
                        while (true) {
                            // 在读取每个分块前检查取消标志
                            if (isCanceled?.invoke() == true) {
                                log { "Upload canceled by user during file reading" }
                                throw kotlinx.coroutines.CancellationException("Upload canceled")
                            }

                            val haveRead = file.read(partBuf)
                            if (haveRead <= 0) break

                            // 发送到 channel,如果 channel 满了会自动阻塞
                            channel.send(pn to partBuf.copyOf(haveRead))
                            pn++
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    log { "Producer canceled: ${e.message}" }
                    throw e
                } finally {
                    channel.close()
                }
            }

            // 消费者协程:并发上传分块
            val consumers = List(concurrency) {
                launch {
                    for ((partNumber, partData) in channel) {
                        try {
                            // 在上传每个分块前检查取消标志
                            if (isCanceled?.invoke() == true) {
                                log { "Upload canceled by user before uploading part $partNumber" }
                                channel.cancel()
                                throw kotlinx.coroutines.CancellationException("Upload canceled")
                            }

                            log { "Uploading part $partNumber" }

                            val uploadPartResponse = s3Client?.uploadPart(
                                UploadPartRequest {
                                    bucket = extra.bucket
                                    key = dstPath
                                    uploadId = createMultipartUploadResponse?.uploadId
                                    this.partNumber = partNumber
                                    body = ByteStream.fromBytes(partData)
                                }
                            )

                            completedParts[partNumber] = CompletedPart {
                                eTag = uploadPartResponse?.eTag
                                this.partNumber = partNumber
                            }

                            // 更新进度
                            val currentUploaded = uploadedBytes.addAndGet(partData.size.toLong())
                            onUploading(currentUploaded, srcFileSize)

                            // 在进度更新后也检查取消标志
                            if (isCanceled?.invoke() == true) {
                                log { "Upload canceled by user after uploading part $partNumber" }
                                channel.cancel()
                                throw kotlinx.coroutines.CancellationException("Upload canceled")
                            }

                            log { "Part $partNumber uploaded successfully" }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            log { "Consumer canceled: ${e.message}" }
                            channel.cancel()
                            throw e
                        } catch (e: Exception) {
                            log { "Part $partNumber upload failed: ${e.message}" }
                            channel.cancel()
                            throw e
                        }
                    }
                }
            }

            try {
                // 等待生产者和所有消费者完成
                producer.join()
                consumers.forEach { it.join() }

                // 显式检查取消状态
                if (isCanceled?.invoke() == true) {
                    throw kotlinx.coroutines.CancellationException("Upload canceled")
                }

                // 正常完成逻辑必须在 try 块内
                val sortedParts = completedParts.toSortedMap().values.toList()

                s3Client?.completeMultipartUpload(
                    CompleteMultipartUploadRequest {
                        bucket = extra.bucket
                        key = dstPath
                        uploadId = createMultipartUploadResponse?.uploadId
                        multipartUpload { parts = sortedParts }
                    }
                )
                uploadIdDao.deleteByUploadId(createMultipartUploadResponse?.uploadId ?: "")
                onUploading(srcFileSize, srcFileSize)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消时中止分片上传
                log { "Upload canceled, aborting multipart upload" }
                runCatching {
                    s3Client?.abortMultipartUpload(
                        AbortMultipartUploadRequest {
                            bucket = extra.bucket
                            key = dstPath
                            uploadId = createMultipartUploadResponse?.uploadId
                        }
                    )
                    uploadIdDao.deleteByUploadId(createMultipartUploadResponse?.uploadId ?: "")
                }.onFailure { abortError ->
                    log { "Failed to abort multipart upload: ${abortError.message}" }
                }
                throw e
            }
        }
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        runBlocking {
            val name = PathUtil.getFileName(src)
            val dstPath = "$dst/$name"
            log { "download: $src to $dstPath" }

            val srcKey = normalizeObjectKey(src)

            // 先调用 HEAD 方法获取对象大小
            val headResponse = s3Client?.headObject(HeadObjectRequest {
                bucket = extra.bucket
                key = srcKey
            })
            val totalSize = headResponse?.contentLength ?: -1L
            log { "HEAD response: total size = $totalSize" }

            try {
                s3Client?.getObject(GetObjectRequest {
                    bucket = extra.bucket
                    key = srcKey
                }) { resp ->
                    val dstFile = File(dstPath)

                    dstFile.parentFile?.mkdirs()
                    log { "Starting download, total size: $totalSize" }

                    coroutineScope {
                        val progressJob = launch {
                            var lastSize = 0L
                            var lastProgressTime = System.currentTimeMillis()

                            while (isActive) {
                                delay(300)

                                val currentSize = try {
                                    if (dstFile.exists()) dstFile.length() else 0L
                                } catch (e: Exception) {
                                    lastSize
                                }

                                val currentTime = System.currentTimeMillis()
                                log { "Progress check: currentSize=$currentSize, totalSize=$totalSize, exists=${dstFile.exists()}" }

                                if (currentSize != lastSize) {
                                    onDownloading(currentSize, totalSize)
                                    log { "Progress updated: $currentSize / $totalSize" }
                                    lastSize = currentSize
                                    lastProgressTime = currentTime
                                }

                                if (totalSize > 0 && currentSize >= totalSize) {
                                    break
                                }

                                // 安全退出：5秒无进度变化
                                if (currentTime - lastProgressTime > 5000 && currentSize > 0) {
                                    log { "No progress for 5 seconds, assuming completion" }
                                    break
                                }
                            }
                        }

                        try {
                            resp.body?.writeToFile(dstFile)
                            val finalSize = dstFile.length()
                            onDownloading(finalSize, totalSize)
                            log { "Download completed: $finalSize bytes" }
                        } catch (e: Exception) {
                            log { "Download error: ${e.message}" }
                            throw e
                        } finally {
                            progressJob.cancel()
                        }
                    }
                }
            } catch (e: Exception) {
                log { "S3 operation failed: ${e.message}" }
                throw e
            }
        }
    }

    override fun deleteFile(src: String) {
        log { "deleteFile: $src" }
        runBlocking {
            s3Client?.deleteObject(DeleteObjectRequest {
                bucket = extra.bucket
                key = normalizeObjectKey(src)
            })
        }
    }

    override fun removeDirectory(src: String): Boolean {
        log { "removeDirectory: $src" }
        return runBlocking {
            try {
                val prefix = normalizeObjectKey(src) + "/"
                val listResponse = s3Client?.listObjectsV2(ListObjectsV2Request {
                    bucket = extra.bucket
                    this.prefix = prefix
                })

                val objectIdentifiers = listResponse?.contents?.mapNotNull { obj ->
                    obj.key?.let { key ->
                        ObjectIdentifier { this.key = key }
                    }
                } ?: emptyList()

                if (objectIdentifiers.isNotEmpty()) {
                    val deleteRequest = Delete {
                        objects = objectIdentifiers
                    }

                    s3Client?.deleteObjects(DeleteObjectsRequest {
                        bucket = extra.bucket
                        delete = deleteRequest
                    })
                }
                true
            } catch (e: Exception) {
                log { "removeDirectory failed: ${e.message}" }
                false
            }
        }
    }

    override fun deleteRecursively(src: String): Boolean {
        log { "deleteRecursively: $src" }
        return removeDirectory(src)
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
        // S3 没有真正的空目录概念
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        log { "listFiles: $src" }
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()

        runBlocking {
            val prefix = if (src.isEmpty()) "" else normalizeObjectKey(src) + "/"
            val response = s3Client?.listObjectsV2(ListObjectsV2Request {
                bucket = extra.bucket
                this.prefix = prefix
                delimiter = "/"
            })

            response?.contents?.forEach { obj ->
                val key = obj.key ?: ""
                if (key != prefix && !key.endsWith("/")) {
                    val name = key.removePrefix(prefix)
                    files.add(FileParcelable(name, obj.lastModified?.epochSeconds ?: 0))
                }
            }

            response?.commonPrefixes?.forEach { commonPrefix ->
                val prefixStr = commonPrefix.prefix ?: ""
                val name = prefixStr.removePrefix(prefix).removeSuffix("/")
                if (name.isNotEmpty()) {
                    directories.add(FileParcelable(name, 0))
                }
            }
        }

        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(path: String): List<PathParcelable> {
        val pathList = mutableListOf<PathParcelable>()
        val prefix = normalizeObjectKey(path) + "/"

        log { "walkFileTree called with path: $path, normalized prefix: $prefix" }
        log { "S3 client state: ${if (s3Client != null) "connected" else "null"}" }

        runBlocking {
            val response = s3Client?.listObjectsV2(ListObjectsV2Request {
                bucket = extra.bucket
                this.prefix = prefix
            })

            log { "ListObjectsV2 response: ${response?.contents?.size ?: 0} objects" }

            response?.contents?.forEach { obj ->
                obj.key?.let { key ->
                    if (!key.endsWith("/")) {
                        pathList.add(PathParcelable(key))
                    }
                }
            }
        }
        log { "walkFileTree returning ${pathList.size} paths" }
        return pathList
    }

    override fun exists(src: String): Boolean = runBlocking {
        try {
            s3Client?.headObject(HeadObjectRequest {
                bucket = extra.bucket
                key = normalizeObjectKey(src)
            })
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun size(src: String): Long = runBlocking {
        try {
            val response = s3Client?.headObject(HeadObjectRequest {
                bucket = extra.bucket
                key = normalizeObjectKey(src)
            })
            response?.contentLength ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun testConnection() {
        connect()
        try {
            s3Client?.listObjectsV2(ListObjectsV2Request {
                bucket = extra.bucket
                maxKeys = 1
            })
        } finally {
            disconnect()
        }
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val currentExtra = entity.getExtraEntity<S3Extra>()!!
        connect()
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            traverseBackend = { listFiles(it.replaceFirst(prefix, "")) },
            mkdirsBackend = { parent, child ->
                runCatching {
                    val path = "$parent/$child".replaceFirst(prefix, "").trim('/')
                    mkdirRecursively(path)
                }.isSuccess
            },
            title = context.getString(R.string.select_target_directory),
            pickerType = PickerType.DIRECTORY,
            rootPathList = listOf(prefix),
            defaultPathList = listOf(prefix),
        )
        withMainContext {
            val pathString = pickYou.awaitLaunch(context)
            val remotePath = pathString.replaceFirst(prefix, "").trim('/')
            onSet(remotePath, GsonUtil().toJson(currentExtra))
        }
        disconnect()
    }
}