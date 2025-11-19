package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.core.model.database.S3Extra

interface CloudClient {
    fun connect()
    fun disconnect()
    fun mkdir(dst: String)
    fun mkdirRecursively(dst: String)
    fun renameTo(
        src: String,
        dst: String,
        onProgress: ((currentPart: Int, totalParts: Int, currentFile: Int, totalFiles: Int) -> Unit)? = null
    )
    fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit)
    fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit)
    fun deleteFile(src: String)
    fun removeDirectory(src: String): Boolean
    fun clearEmptyDirectoriesRecursively(src: String)
    fun deleteRecursively(src: String): Boolean
    fun listFiles(src: String): DirChildrenParcelable
    fun walkFileTree(src: String): List<PathParcelable>
    fun exists(src: String): Boolean
    fun size(src: String): Long
    suspend fun testConnection()
    suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit)
}

fun CloudEntity.getCloud() = when (this.type) {
    CloudType.FTP -> {
        val extra = getExtraEntity<FTPExtra>()!!
        FTPClientImpl(this, extra)
    }

    CloudType.WEBDAV -> {
        val extra = getExtraEntity<WebDAVExtra>()!!
        WebDAVClientImpl(this, extra)
    }

    CloudType.SMB -> {
        val extra = getExtraEntity<SMBExtra>()!!
        SMBClientImpl(this, extra)
    }

    CloudType.SFTP -> {
        val extra = getExtraEntity<SFTPExtra>()!!
        SFTPClientImpl(this, extra)
    }

    CloudType.S3 -> {
        val extra = getExtraEntity<S3Extra>()!!
        S3ClientImpl(this, extra)
    }
}
