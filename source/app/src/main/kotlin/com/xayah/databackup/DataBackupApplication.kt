package com.xayah.databackup

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.database.dao.UploadIdDao
import com.xayah.core.network.client.S3ClientImpl
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class DataBackupApplication : Application(), Configuration.Provider {
    companion object {
        lateinit var application: Application
    }

    @Inject
    lateinit var uploadIdDao: UploadIdDao

    @Inject
    lateinit var cloudDao: CloudDao

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        application = this
        setupBouncyCastle()

        // 启动时清理残留的 uploadId
        GlobalScope.launch(Dispatchers.IO) {
            cleanupOrphanedUploads()
        }
    }

    private suspend fun cleanupOrphanedUploads() {
        try {
            val orphanedUploads = uploadIdDao.getAll()

            if (orphanedUploads.isEmpty()) {
                Log.d("DataBackupApplication", "No orphaned uploads found")
                return
            }

            Log.d("DataBackupApplication", "Found ${orphanedUploads.size} orphaned uploads, starting cleanup")

            orphanedUploads.forEach { entity ->
                try {
                    // 获取对应的云存储配置
                    val cloudEntity = cloudDao.queryByName(entity.cloudName)
                    if (cloudEntity == null) {
                        Log.w("DataBackupApplication", "Cloud entity not found for ${entity.cloudName}, deleting record")
                        uploadIdDao.deleteById(entity.id)
                        return@forEach
                    }

                    // 调用 S3ClientImpl 的静态清理方法
                    Log.d("DataBackupApplication", "Cleaning up uploadId: ${entity.uploadId}")
                    S3ClientImpl.cleanupOrphanedUpload(entity, cloudEntity)

                    // 清理成功后删除数据库记录
                    uploadIdDao.deleteById(entity.id)
                    Log.d("DataBackupApplication", "Successfully cleaned up uploadId: ${entity.uploadId}")
                } catch (e: Exception) {
                    Log.e("DataBackupApplication", "Failed to cleanup uploadId ${entity.uploadId}: ${e.message}")
                    // 即使清理失败也删除记录,避免永久残留
                    uploadIdDao.deleteById(entity.id)
                }
            }

            Log.d("DataBackupApplication", "Orphaned uploads cleanup completed")
        } catch (e: Exception) {
            Log.e("DataBackupApplication", "Error during orphaned uploads cleanup: ${e.message}")
        }
    }

    private fun setupBouncyCastle() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) ?: return

        if (provider.javaClass == BouncyCastleProvider::class.java) {
            return
        }

        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}