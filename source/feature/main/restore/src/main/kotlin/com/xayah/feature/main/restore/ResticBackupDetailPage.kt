package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.model.OpType
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.encodedURLWithSpace
import com.xayah.core.model.Target
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.ProgressButton
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.model.DataType
import com.xayah.feature.main.restore.R
import com.xayah.feature.main.restore.ResticBackupGroup
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticBackupDetailPage(
    navController: NavController,
    group: ResticBackupGroup,
    viewModel: ResticRestoreViewModel = hiltViewModel()
) {
    val resticProgress by viewModel.resticProgress.collectAsStateWithLifecycle()
    val dialogState = LocalSlotScope.current!!.dialogSlot  // 新增
    val coroutineScope = rememberCoroutineScope()
    val noticeText = stringResource(R.string.restore_dialog_notice)  // 新增：提前 hoist 出对话框标题
    val confirmDeleteText = stringResource(R.string.restore_confirm_delete_local_app, group.backups.size)
    val hasConfigSnapshot = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
    // 新增删除状态变量
    val isDeleting = resticProgress.isDeleting
    val isRestoring = resticProgress.totalDataTypes > 0 &&
            resticProgress.currentDataTypeIndex < resticProgress.totalDataTypes &&
            !isDeleting  // 排除删除状态

    val isCompleted = resticProgress.isCompleted && !isDeleting
    val deleteButtonEnabled = !isRestoring && !isCompleted && !isDeleting
    val restoreButtonEnabled = !isRestoring && !isCompleted && !isDeleting && hasConfigSnapshot

    // 新增删除进度相关变量
    val totalSnapshots = group.backups.size
    val totalSteps = totalSnapshots + 1  // 快照数量 + 1 (prune)
    val currentStep = if (isDeleting) {
        resticProgress.currentDataTypeIndex + 1
    } else {
        0
    }

    // 保留原有的 getCurrentDataTypeName 函数
    fun getCurrentDataTypeName(group: ResticBackupGroup, index: Int): String {
        val sortedBackups = group.backups.sortedBy { backup ->
            when (backup.dataType) {
                DataType.PACKAGE_APK -> 0
                DataType.PACKAGE_USER -> 1
                DataType.PACKAGE_USER_DE -> 2
                DataType.PACKAGE_DATA -> 3
                DataType.PACKAGE_OBB -> 4
                DataType.PACKAGE_MEDIA -> 5
                DataType.PACKAGE_CONFIG -> 6
                else -> 7
            }
        }
        return if (index < sortedBackups.size) {
            sortedBackups[index].dataType.type.uppercase()
        } else ""
    }

    // 保留原有的进度计算变量
    val currentProgress = if (resticProgress.bytesTotal > 0) {
        resticProgress.bytesWritten.toFloat() / resticProgress.bytesTotal
    } else 0f

    val currentIndex = resticProgress.currentDataTypeIndex
    val totalCount = resticProgress.totalDataTypes
    val speed = resticProgress.speed
    val progressSize = "${resticProgress.bytesWritten.formatSize()}/${resticProgress.bytesTotal.formatSize()}"
    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState()
        ),
        title = stringResource(R.string.restore_backup_detail_title),
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
            ) {
                // 删除按钮
                ProgressButton(
                    modifier = Modifier.fillMaxWidth(),
                    progress = 0f,
                    currentIndex = if (isDeleting) currentStep else 0,
                    totalCount = if (isDeleting) totalSteps else 0,
                    speed = "",
                    progressSize = "",
                    enabled = deleteButtonEnabled,
                    text = if (isDeleting) {
                        if (currentStep <= totalSnapshots) {
                            val currentDataType = getCurrentDataTypeName(group, currentIndex)
                            stringResource(R.string.restore_deleting_snapshot, currentDataType, currentStep, totalSteps)
                        } else {
                            stringResource(R.string.restore_cleaning_storage, totalSteps, totalSteps)
                        }
                    } else {
                        stringResource(R.string.restore_delete_local_snapshot)
                    },
                    onClick = {
                        if (!isDeleting) {
                            coroutineScope.launch {
                                if (dialogState.confirm(
                                        title = noticeText,
                                        text = confirmDeleteText
                                    )) {
                                    val success = viewModel.deleteLocalSnapshots(group)
                                    if (success) {
                                        // 通知上一级列表页：需要强制刷新（绕过 hasLoaded 守卫）
                                        navController.previousBackStackEntry
                                            ?.savedStateHandle
                                            ?.set("restic_needs_refresh", true)
                                        navController.popBackStack()
                                    }
                                }
                            }
                        }
                    }
                )

                // 恢复按钮 (保持原有逻辑,但修改 enabled 为 restoreButtonEnabled)
                ProgressButton(
                    modifier = Modifier.fillMaxWidth(),
                    progress = currentProgress,
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    speed = speed,
                    progressSize = progressSize,
                    enabled = restoreButtonEnabled && hasConfigSnapshot,  // 修改这里
                    text = when {
                        !hasConfigSnapshot -> stringResource(R.string.restore_incomplete_cannot_restore)  // 新增,放在最前面
                        isRestoring -> {
                            val currentDataType = getCurrentDataTypeName(group, currentIndex)
                            stringResource(R.string.restore_restoring_snapshot, currentDataType)
                        }
                        isCompleted -> stringResource(R.string.restore_snapshot_restore_completed)
                        else -> stringResource(R.string.restore_restore_snapshot_backup)
                    },
                    onClick = {
                        if (!isRestoring && !isCompleted && !isDeleting && hasConfigSnapshot) {  // 添加 hasConfigSnapshot 检查
                            Log.d("ResticRestore", "用户点击恢复按钮，开始恢复流程")
                            coroutineScope.launch {
                                try {
                                    val success = viewModel.restoreFromResticSnapshots(group)
                                    if (success) {
                                        val backupDir = "${viewModel.readBackupDirectory()}/restore/"
                                        viewModel.refreshLocalDatabase(backupDir)
                                        viewModel.calculateSizesForActivatedApps()
                                        val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                            cloudName = encodedURLWithSpace,
                                            backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                            packageName = group.packageName
                                        )
                                        navController.navigateSingle(route)
                                    }
                                } catch (e: Exception) {
                                    Log.e("ResticRestore", "恢复流程异常: ${e.message}", e)
                                }
                            }
                        }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
                .verticalScroll(rememberScrollState())
        ) {
            // APP图标和基本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
            ) {
                PackageIconImage(
                    packageName = group.packageName,
                    size = SizeTokens.Level64,
                    accountId = "local"
                )

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TitleLargeText(text = group.appLabel)
                        if (group.userId != 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_clone_app),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    BodyMediumText(
                        text = group.packageName,
                        color = ThemedColorSchemeKeyTokens.Outline.value
                    )
                    BodyMediumText(
                        text = DateUtil.formatTimestamp(
                            group.timestamp,
                            DateUtil.PATTERN_YMD_HMS
                        ),
                        color = ThemedColorSchemeKeyTokens.Outline.value
                    )
                }
            }

            Spacer(modifier = Modifier.height(SizeTokens.Level24))

            // 备份类型详情
            Title(title = stringResource(R.string.restore_backup_type_details)) {
                group.backups.forEach { backup ->
                    Column(
                        modifier = Modifier.padding(vertical = SizeTokens.Level8)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = backup.dataType.type.uppercase(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.restore_snapshot_id, backup.snapshotId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.restore_snapshot_size, backup.totalBytesProcessed.formatSize()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = SizeTokens.Level4)
                        )
                    }
                }
            }
        }
    }
}

private fun Long.formatSize(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> String.format("%.2f KiB", this / 1024.0)
        this < 1024 * 1024 * 1024 -> String.format("%.2f MiB", this / (1024.0 * 1024))
        else -> String.format("%.2f GiB", this / (1024.0 * 1024 * 1024))
    }
}