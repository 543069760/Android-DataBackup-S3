package com.xayah.feature.main.settings.cache

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CacheManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val pathUtil: PathUtil,
) : ViewModel() {

    data class CacheInfo(
        val appCacheSize: Long = 0L,
        val fileCacheSize: Long = 0L,
        val isCalculating: Boolean = false
    )

    private val _cacheInfo = MutableStateFlow(CacheInfo())
    val cacheInfo: StateFlow<CacheInfo> = _cacheInfo.asStateFlow()

    // 计算缓存大小
    fun calculateCacheSize() {
        viewModelScope.launch {
            _cacheInfo.update { it.copy(isCalculating = true) }

            // 计算 APP 缓存大小
            val appCacheSize = rootService.calculateSize(context.cacheDir.path)

            // 计算文件缓存大小
            val fileCacheSize = rootService.calculateSize(pathUtil.getCloudTmpDir())

            _cacheInfo.update {
                it.copy(
                    appCacheSize = appCacheSize,
                    fileCacheSize = fileCacheSize,
                    isCalculating = false
                )
            }
        }
    }

    // 清除 APP 缓存
    fun clearAppCache() {
        viewModelScope.launch {
            rootService.deleteRecursively(context.cacheDir.path)
            context.cacheDir.mkdirs()
            calculateCacheSize()
        }
    }

    // 清除文件缓存
    fun clearFileCache() {
        viewModelScope.launch {
            rootService.deleteRecursively(pathUtil.getCloudTmpDir())
            File(pathUtil.getCloudTmpDir()).mkdirs()
            calculateCacheSize()
        }
    }

    // 清除所有缓存
    fun clearAllCache() {
        viewModelScope.launch {
            rootService.deleteRecursively(context.cacheDir.path)
            context.cacheDir.mkdirs()
            rootService.deleteRecursively(pathUtil.getCloudTmpDir())
            File(pathUtil.getCloudTmpDir()).mkdirs()
            calculateCacheSize()
        }
    }
}