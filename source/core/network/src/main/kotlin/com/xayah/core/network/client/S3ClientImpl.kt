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
import kotlinx.coroutines.runBlocking
import java.io.File

class S3ClientImpl(
    private val entity: CloudEntity,
    private val extra: S3Extra
) : CloudClient {
    private var s3Client: S3Client? = null

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "S3ClientImpl" to msg() }
        msg()
    }

    private fun normalizeObjectKey(path: String): String {
        return path.trim('/').replace("//", "/")
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
                endpointUrl = Url.parse("https://${extra.endpoint}")
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

    override fun renameTo(src: String, dst: String) {
        runBlocking {
            s3Client?.copyObject(CopyObjectRequest {
                copySource = "${extra.bucket}/${normalizeObjectKey(src)}"
                bucket = extra.bucket
                key = normalizeObjectKey(dst)
            })
            s3Client?.deleteObject(DeleteObjectRequest {
                bucket = extra.bucket
                key = normalizeObjectKey(src)
            })
        }
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) {
        runBlocking {
            val name = PathUtil.getFileName(src)
            val dstPath = normalizeObjectKey("$dst/$name")
            log { "upload: $src to $dstPath" }

            val srcFile = File(src)
            val srcFileSize = srcFile.length()

            val createMultipartUploadResponse = s3Client?.createMultipartUpload(
                CreateMultipartUploadRequest {
                    bucket = extra.bucket
                    key = dstPath
                }
            )

            val PART_SIZE = 5 * 1024 * 1024
            val partBuf = ByteArray(PART_SIZE)
            val completedParts = mutableListOf<CompletedPart>()
            var uploadedBytes = 0L

            // 速度计算变量
            var lastUpdateTime = System.currentTimeMillis()
            var lastUploadedBytes = 0L
            var currentSpeed = 0L

            srcFile.inputStream().buffered().use { file ->
                var pn = 1
                while (true) {
                    val haveRead = file.read(partBuf)
                    if (haveRead <= 0) break

                    val uploadPartResponse = s3Client?.uploadPart(
                        UploadPartRequest {
                            bucket = extra.bucket
                            key = dstPath
                            uploadId = createMultipartUploadResponse?.uploadId
                            partNumber = pn
                            body = ByteStream.fromBytes(partBuf.copyOf(haveRead))
                        }
                    )

                    completedParts.add(
                        CompletedPart {
                            eTag = uploadPartResponse?.eTag
                            partNumber = pn
                        }
                    )

                    uploadedBytes += haveRead

                    // 计算速度
                    val currentTime = System.currentTimeMillis()
                    val timeDiff = currentTime - lastUpdateTime
                    if (timeDiff >= 500) {
                        val bytesDiff = uploadedBytes - lastUploadedBytes
                        currentSpeed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                        lastUpdateTime = currentTime
                        lastUploadedBytes = uploadedBytes
                    }

                    onUploading(uploadedBytes, srcFileSize)
                    pn++
                }
            }

            s3Client?.completeMultipartUpload(
                CompleteMultipartUploadRequest {
                    bucket = extra.bucket
                    key = dstPath
                    uploadId = createMultipartUploadResponse?.uploadId
                    multipartUpload { parts = completedParts }
                }
            )

            onUploading(srcFileSize, srcFileSize)
        }
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        runBlocking {
            val name = PathUtil.getFileName(src)
            val dstPath = "$dst/$name"
            log { "download: $src to $dstPath" }

            val srcKey = normalizeObjectKey(src)
            s3Client?.getObject(GetObjectRequest {
                bucket = extra.bucket
                key = srcKey
            }) { resp ->
                val dstFile = File(dstPath)
                resp.body?.writeToFile(dstFile)
                val fileSize = dstFile.length()
                onDownloading(fileSize, fileSize)
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

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathList = mutableListOf<PathParcelable>()
        runBlocking {
            val prefix = normalizeObjectKey(src)
            val response = s3Client?.listObjectsV2(ListObjectsV2Request {
                bucket = extra.bucket
                this.prefix = prefix
            })

            response?.contents?.forEach { obj ->
                obj.key?.let { key ->
                    if (!key.endsWith("/")) {
                        pathList.add(PathParcelable(key))
                    }
                }
            }
        }
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