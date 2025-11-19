package com.xayah.feature.main.details

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.LogUtil
import com.xayah.core.util.module.combine
import com.xayah.core.util.launchOnDefault
import com.xayah.core.util.withMainContext
import com.xayah.feature.main.details.DetailsUiState.Error
import com.xayah.feature.main.details.DetailsUiState.Loading
import com.xayah.feature.main.details.DetailsUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val rootService: RemoteRootService,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
) : ViewModel() {
    private val id: Long = savedStateHandle.get<String>(MainRoutes.ARG_ID)?.toLongOrNull() ?: 0L
    private val target: Target = Target.valueOf(savedStateHandle.get<String>(MainRoutes.ARG_TARGET)!!.decodeURL().trim())
    private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    // 添加 isProtecting 状态
    private val isProtecting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    // 添加 protectProgress 状态
    private val protectProgress: MutableStateFlow<String?> = MutableStateFlow(null)

    companion object {
        private const val TAG = "DetailsViewModel"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    val uiState: StateFlow<DetailsUiState> = when (target) {
        Target.Apps -> {
            combine(
                appsRepo.getApp(id),
                isRefreshing,
                isProtecting,
                protectProgress,  // 添加这个
                labelsRepo.getLabelsFlow(),
                labelsRepo.getAppRefsFlow()
            ) { app, isRefreshing, isProtecting, protectProgress, labels, refs ->
                if (app != null) {
                    Success.App(
                        uuid = UUID.randomUUID(),
                        isRefreshing = isRefreshing,
                        isProtecting = isProtecting,
                        protectProgress = protectProgress,  // 添加这个
                        labels = labels,
                        app = app,
                        refs = refs.filter { ref ->
                            labels.find { it.label == ref.label } != null &&
                                    ref.packageName == app.packageName &&
                                    ref.userId == app.userId &&
                                    ref.preserveId == app.preserveId
                        }
                    )
                } else {
                    Error
                }
            }
        }

        Target.Files -> {
            combine(
                filesRepo.getFile(id),
                isRefreshing,
                isProtecting,
                protectProgress,  // 添加这个
                labelsRepo.getLabelsFlow(),
                labelsRepo.getFileRefsFlow()
            ) { file, isRefreshing, isProtecting, protectProgress, labels, refs ->
                if (file != null) {
                    Success.File(
                        uuid = UUID.randomUUID(),
                        isRefreshing = isRefreshing,
                        isProtecting = isProtecting,
                        protectProgress = protectProgress,  // 添加这个
                        labels = labels,
                        file = file,
                        refs = refs.filter { ref ->
                            labels.find { it.label == ref.label } != null &&
                                    ref.path == file.path &&
                                    ref.preserveId == file.preserveId
                        }
                    )
                } else {
                    Error
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun onResume() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state: Success.App = uiState.value.castTo()
                    when (state.app.indexInfo.opType) {
                        OpType.BACKUP -> {
                            isRefreshing.emit(true)
                            appsRepo.updateApp(state.app, state.app.userId)
                            appsRepo.calculateLocalAppSize(state.app)
                            isRefreshing.emit(false)
                        }

                        OpType.RESTORE -> {
                            isRefreshing.emit(true)
                            if (state.app.indexInfo.cloud.isNotEmpty()) {
                                appsRepo.calculateCloudAppArchiveSize(state.app.indexInfo.cloud, state.app)
                            } else {
                                appsRepo.calculateLocalAppArchiveSize(state.app)
                            }
                            isRefreshing.emit(false)
                        }
                    }
                }

                is Success.File -> {
                    val state: Success.File = uiState.value.castTo()
                    when (state.file.indexInfo.opType) {
                        OpType.BACKUP -> {
                            isRefreshing.emit(true)
                            filesRepo.calculateLocalFileSize(state.file)
                            isRefreshing.emit(false)
                        }

                        OpType.RESTORE -> {
                            isRefreshing.emit(true)
                            if (state.file.indexInfo.cloud.isNotEmpty()) {
                                filesRepo.calculateCloudFileArchiveSize(state.file.indexInfo.cloud, state.file)
                            } else {
                                filesRepo.calculateLocalFileArchiveSize(state.file)
                            }
                            isRefreshing.emit(false)
                        }
                    }
                }

                else -> {}
            }
        }
    }

    fun setDataStates(id: Long, dataStates: PackageDataStates) {
        viewModelScope.launchOnDefault {
            appsRepo.setDataItems(listOf(id), dataStates)
        }
    }

    fun addLabel(label: String) {
        viewModelScope.launchOnDefault {
            if (label.trim().isEmpty() || labelsRepo.addLabel(label.trim()) == -1L) {
                withMainContext {
                    Toast.makeText(context, context.getString(R.string.failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun deleteLabel(label: String) {
        viewModelScope.launchOnDefault {
            labelsRepo.deleteLabel(label)
        }
    }

    fun selectAppLabel(selected: Boolean, ref: LabelAppCrossRefEntity?) {
        viewModelScope.launchOnDefault {
            if (ref != null) {
                if (selected) {
                    labelsRepo.deleteLabelAppCrossRef(ref)
                } else {
                    labelsRepo.addLabelAppCrossRef(ref)
                }
            }
        }
    }

    fun selectFileLabel(selected: Boolean, ref: LabelFileCrossRefEntity?) {
        viewModelScope.launchOnDefault {
            if (ref != null) {
                if (selected) {
                    labelsRepo.deleteLabelFileCrossRef(ref)
                } else {
                    labelsRepo.addLabelFileCrossRef(ref)
                }
            }
        }
    }

    fun block(blocked: Boolean) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    appsRepo.blockByIds(listOf(state.app.id))
                }

                is Success.File -> {
                    val state = uiState.value.castTo<Success.File>()
                    filesRepo.blockByIds(listOf(state.file.id))
                }

                else -> {}
            }
        }
    }

    fun freezeApp(frozen: Boolean) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    if (frozen) {
                        rootService.setApplicationEnabledSetting(app.packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, app.userId, null)
                    } else {
                        rootService.setApplicationEnabledSetting(app.packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, app.userId, null)
                    }
                    appsRepo.setEnabled(app.id, rootService.getApplicationEnabledSetting(app.packageName, app.userId) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                }

                else -> {}
            }
        }
    }

    fun launchApp() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    appsRepo.launchApp(app.packageName, app.userId)
                }

                else -> {}
            }
        }
    }

    fun protect() {
        viewModelScope.launchOnDefault {
            log { "protect() started" }  // 1. 方法开始
            isProtecting.emit(true)
            protectProgress.emit(null)
            try {
                when (uiState.value) {
                    is Success.App -> {
                        val state = uiState.value.castTo<Success.App>()
                        val app = state.app
                        log { "Protecting app: ${app.packageName}" }
                        log { "Calling appsRepo.protectApp with progress callback" }

                        appsRepo.protectApp(app.indexInfo.cloud, app) { currentPart, totalParts, currentFile, totalFiles ->
                            log { "Progress callback received in ViewModel: Part $currentPart/$totalParts, File $currentFile/$totalFiles" }
                            viewModelScope.launch {
                                protectProgress.emit("$currentPart/$totalParts")
                                log { "protectProgress updated to: $currentPart/$totalParts" }
                            }
                        }

                        log { "appsRepo.protectApp completed" }
                    }

                    else -> {
                        val state = uiState.value.castTo<Success.File>()
                        val file = state.file
                        log { "Protecting file: ${file.name}, cloud: ${file.indexInfo.cloud}" }  // 3. File 分支开始
                        log { "Calling filesRepo.protectFile with progress callback" }  // 4. 调用前

                        filesRepo.protectFile(file.indexInfo.cloud, file) { currentPart, totalParts, currentFile, totalFiles ->
                            log { "Progress callback received in ViewModel: Part $currentPart/$totalParts, File $currentFile/$totalFiles" }  // 5. 回调被触发
                            viewModelScope.launch {
                                protectProgress.emit("$currentPart/$totalParts")
                                log { "protectProgress updated to: $currentPart/$totalParts" }  // 6. 状态更新后
                            }
                        }
                        log { "filesRepo.protectFile completed" }  // 7. 调用完成
                    }
                }
            } catch (e: Exception) {
                log { "protect() failed with exception: ${e.message}" }  // 8. 异常捕获
            } finally {
                log { "protect() finally block - cleaning up" }  // 9. 清理前
                isProtecting.emit(false)
                protectProgress.emit(null)
                log { "protect() completed" }  // 10. 方法结束
            }
        }
    }

    fun delete() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    when (app.indexInfo.opType) {
                        OpType.BACKUP -> {
                            appsRepo.delete(app.id)
                        }

                        OpType.RESTORE -> {
                            appsRepo.deleteApp(app.indexInfo.cloud, app)
                        }
                    }
                }

                is Success.File -> {
                    val state = uiState.value.castTo<Success.File>()
                    val file = state.file
                    when (file.indexInfo.opType) {
                        OpType.BACKUP -> {
                            filesRepo.delete(file.id)
                        }

                        OpType.RESTORE -> {
                            filesRepo.deleteFile(file.indexInfo.cloud, file)
                        }
                    }
                }

                else -> {}
            }
        }
    }
}

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    sealed class Success(
        open val uuid: UUID,
        open val isRefreshing: Boolean,
        open val isProtecting: Boolean,
        open val protectProgress: String?,  // 添加这个字段
        open val labels: List<LabelEntity>,
    ) : DetailsUiState {
        data class App(
            override val uuid: UUID,
            override val isRefreshing: Boolean,
            override val isProtecting: Boolean,
            override val protectProgress: String?,  // 添加这个字段
            override val labels: List<LabelEntity>,
            val app: PackageEntity,
            val refs: List<LabelAppCrossRefEntity>,
        ) : Success(uuid, isRefreshing, isProtecting, protectProgress, labels)

        data class File(
            override val uuid: UUID,
            override val isRefreshing: Boolean,
            override val isProtecting: Boolean,
            override val protectProgress: String?,  // 添加这个字段
            override val labels: List<LabelEntity>,
            val file: MediaEntity,
            val refs: List<LabelFileCrossRefEntity>,
        ) : Success(uuid, isRefreshing, isProtecting, protectProgress, labels)
    }

    data object Error : DetailsUiState
}