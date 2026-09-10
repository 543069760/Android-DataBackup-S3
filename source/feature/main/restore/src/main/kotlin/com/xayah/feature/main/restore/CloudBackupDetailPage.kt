package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.xayah.feature.main.restore.R
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.model.DataType
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.ProgressButton
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.encodeAccountId
import com.xayah.core.util.decodeURL
import com.xayah.core.util.encodedURLWithSpace
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun CloudBackupDetailPage(
    navController: NavController,
    group: ResticBackupGroup,
    accountName: String,
    viewModel: CloudRestoreViewModel = hiltViewModel()
) {
    val resticProgress by viewModel.resticProgress.collectAsStateWithLifecycle()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 对话框标题（非 Composable lambda 内无法调用 stringResource，先在此取好）
    val noticeText = stringResource(R.string.restore_dialog_notice)
    // 删除确认文案同样 hoist（lambda 内不能调用 stringResource）
    val confirmDeleteText = stringResource(R.string.restore_confirm_delete_cloud_app, group.backups.size)

    LaunchedEffect(accountName) {
        viewModel.setCloudEntity(accountName)
    }
    val hasConfigSnapshot = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
    val isDeleting = resticProgress.isDeleting
    val isRestoring = resticProgress.totalDataTypes > 0 &&
            resticProgress.currentDataTypeIndex < resticProgress.totalDataTypes &&
            !isDeleting

    val isCompleted = resticProgress.isCompleted && !isDeleting
    val deleteButtonEnabled = !isRestoring && !isCompleted && !isDeleting
    val restoreButtonEnabled = !isRestoring && !isCompleted && !isDeleting && hasConfigSnapshot

    val currentProgress = if (resticProgress.bytesTotal > 0) {
        resticProgress.bytesWritten.toFloat() / resticProgress.bytesTotal
    } else 0f

    val currentIndex = resticProgress.currentDataTypeIndex
    val totalCount = resticProgress.totalDataTypes
    val speed = resticProgress.speed
    val progressSize = "${resticProgress.bytesWritten.formatSize()} / ${resticProgress.bytesTotal.formatSize()}"

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

    val totalSnapshots = group.backups.size
    val totalSteps = totalSnapshots + 1  // 快照数量 + 1 (prune)
    val currentStep = if (isDeleting) {
        resticProgress.currentDataTypeIndex + 1
    } else {
        0
    }
    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        stringResource(R.string.restore_cloud_backup_detail_title),
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
                        stringResource(R.string.restore_delete_cloud_snapshot)
                    },
                    onClick = {
                        if (!isDeleting) {
                            coroutineScope.launch {
                                if (dialogState.confirm(
                                        title = noticeText,
                                        text = confirmDeleteText
                                    )) {
                                    val success = viewModel.deleteCloudSnapshots(group)
                                    if (success) {
                                        navController.previousBackStackEntry
                                            ?.savedStateHandle
                                            ?.set("cloud_needs_refresh", true)
                                        navController.popBackStack()
                                    }
                                }
                            }
                        }
                    }
                )

                // 恢复按钮
                ProgressButton(
                    modifier = Modifier.fillMaxWidth(),
                    progress = currentProgress,
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    speed = speed,
                    progressSize = progressSize,
                    enabled = restoreButtonEnabled && hasConfigSnapshot,
                    text = when {
                        !hasConfigSnapshot -> stringResource(R.string.restore_backup_incomplete)
                        isRestoring -> {
                            val currentDataType = getCurrentDataTypeName(group, currentIndex)
                            stringResource(R.string.restore_restoring_snapshot, currentDataType)
                        }
                        isCompleted -> stringResource(R.string.restore_cloud_restore_completed)
                        else -> stringResource(R.string.restore_restore_cloud_snapshot)
                    },
                    onClick = {
                        if (!isRestoring && !isCompleted && !isDeleting) {
                            coroutineScope.launch {
                                try {
                                    Log.d("CloudRestore", "用户点击恢复按钮，开始云端恢复流程")
                                    val success = viewModel.restoreFromCloudSnapshots(group)
                                    Log.d("CloudRestore", "云端恢复结果: $success")

                                    if (success) {
                                        Log.d("CloudRestore", "云端恢复成功，准备读取备份目录")
                                        val backupDir = "${context.localBackupSaveDir()}/restore/"
                                        Log.d("CloudRestore", "导航到恢复页面，备份目录: $backupDir")
                                        viewModel.refreshLocalDatabase(backupDir)
                                        viewModel.calculateSizesForActivatedApps()

                                        val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                            cloudName = encodedURLWithSpace,
                                            backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                            packageName = group.packageName
                                        )
                                        Log.d("Navigation", "构建路由: $route")
                                        navController.navigateSingle(route)
                                        Log.d("Navigation", "导航完成: CloudBackupDetailPage → PackagesRestoreProcessingGraph")
                                    } else {
                                        Log.e("CloudRestore", "云端恢复失败")
                                    }
                                } catch (e: Exception) {
                                    Log.e("CloudRestore", "云端恢复流程异常: ${e.message}", e)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
            ) {
                PackageIconImage(
                    packageName = group.packageName,
                    size = SizeTokens.Level64,
                    accountId = encodeAccountId(accountName.replace("accountName=", "").decodeURL())
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
                        text = stringResource(R.string.restore_cloud_account, accountName),
                        color = ThemedColorSchemeKeyTokens.Outline.value
                    )
                    BodyMediumText(
                        text = DateUtil.formatTimestamp(group.timestamp, DateUtil.PATTERN_YMD_HMS),
                        color = ThemedColorSchemeKeyTokens.Outline.value
                    )
                }
            }

            Spacer(modifier = Modifier.height(SizeTokens.Level24))

            Title(title = stringResource(R.string.restore_backup_type_details)) {
                group.backups.forEach { backup ->
                    Column(modifier = Modifier.padding(vertical = SizeTokens.Level8)) {
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = SizeTokens.Level4))
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