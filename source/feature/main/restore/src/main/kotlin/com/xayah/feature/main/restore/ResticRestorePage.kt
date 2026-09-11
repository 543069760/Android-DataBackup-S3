package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.xayah.core.model.DataType
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.feature.main.restore.ResticBackupGroup
import com.xayah.feature.main.restore.R
import android.content.Context
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.util.encodedURLWithSpace
import com.xayah.core.util.navigateSingle
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticRestorePage(
    navController: NavController,
    viewModel: ResticRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val iconVersion by viewModel.iconVersion.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // 已选 group 的 key 集合（不再有多选态开关，复选框常驻）
    val selectedKeys = remember { mutableStateListOf<String>() }
    var isPreparing by remember { mutableStateOf(false) }

    fun groupKey(g: ResticBackupGroup): String = "${g.userId}-${g.packageName}-${g.timestamp}"

    // 互斥选择：勾选某版本前，先剔除已选集合里同一 (packageName, userId) 的其它版本，再加入当前 key
    // 务必同时比较 packageName 与 userId，避免误伤分身(userId != 0)
    fun selectExclusive(g: ResticBackupGroup) {
        val key = groupKey(g)
        // 剔除同一 (packageName, userId) 的其它已选版本（同 packageName 同 userId 但不同 timestamp）
        val prefix = "${g.userId}-${g.packageName}-"
        selectedKeys.removeAll { it.startsWith(prefix) && it != key }
        if (!selectedKeys.contains(key)) selectedKeys.add(key)
    }

    fun exitSelection() {
        selectedKeys.clear()
    }

    LaunchedEffect(Unit) {
        viewModel.loadBackedUpApps()      // 首次/正常进入：守卫生效，秒开
    }

// 删除返回时的强制刷新信号
    val needsRefresh = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("restic_needs_refresh", false)
        ?.collectAsStateWithLifecycle()

    LaunchedEffect(needsRefresh?.value) {
        if (needsRefresh?.value == true) {
            viewModel.forceReload()       // 绕过守卫，重列 + 重建缓存
            exitSelection()               // 列表刷新后清空已选，避免残留脏选择
            navController.currentBackStackEntry
                ?.savedStateHandle
                ?.set("restic_needs_refresh", false)  // 复位，避免重复触发
        }
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = if (selectedKeys.isNotEmpty())
            stringResource(R.string.restore_selected_count, selectedKeys.size)
        else
            stringResource(R.string.restore_restic_restore_title),
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedKeys.isNotEmpty(),
                enter = scaleIn(),
                exit = scaleOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isPreparing) return@ExtendedFloatingActionButton
                        val groups = (uiState as? ResticRestoreUiState.Success)?.groups ?: emptyList()
                        val selectedGroups = groups.filter { selectedKeys.contains(groupKey(it)) }
                        if (selectedGroups.isEmpty()) return@ExtendedFloatingActionButton
                        isPreparing = true
                        coroutineScope.launch {
                            try {
                                // 仅写队列 + 解 config + 刷 DB（重型 tar 交给服务层解出）
                                val success = viewModel.prepareBatchRestore(selectedGroups)
                                if (success) {
                                    val backupDir = "${viewModel.readBackupDirectory()}/restore/"
                                    val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                        cloudName = encodedURLWithSpace,
                                        backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                        packageName = ""
                                    )
                                    exitSelection()
                                    navController.navigateSingle(route)
                                } else {
                                    Log.e("ResticRestorePage", "prepareBatchRestore 返回 false，无可恢复项")
                                }
                            } catch (e: Exception) {
                                Log.e("ResticRestorePage", "批量恢复准备异常: ${e.message}", e)
                            } finally {
                                isPreparing = false
                            }
                        }
                    },
                    icon = { Icon(Icons.Rounded.ChevronRight, null) },
                    text = {
                        Text(
                            if (isPreparing) stringResource(R.string.processing)
                            else stringResource(R.string.restore_next_step)
                        )
                    },
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            val currentState = uiState
            when (currentState) {
                is ResticRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // 修改 Success 状态的处理
                is ResticRestoreUiState.Success -> {
                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_no_backup))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            // 修正后的 items 调用
                            items(
                                currentState.groups,
                                key = { item: ResticBackupGroup -> "${item.userId}-${item.packageName}-${item.timestamp}" }
                            ) { group: ResticBackupGroup ->
                                val key = groupKey(group)
                                val selectable = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                                ResticBackupGroupItem(
                                    group = group,
                                    selectable = selectable,
                                    selected = selectedKeys.contains(key),
                                    onSelectedChange = { checked ->
                                        // 仅可选（含 config）的分组可切换
                                        if (!selectable) return@ResticBackupGroupItem
                                        if (checked) {
                                            // 互斥：同一 (packageName, userId) 只保留当前版本
                                            selectExclusive(group)
                                        } else {
                                            selectedKeys.remove(key)
                                        }
                                    },
                                    onClick = {
                                        // 行点击统一进入详情页（勾选交给右侧复选框）
                                        val groupJson = Json.encodeToString(group)
                                        Log.d("ResticRestorePage", "Navigating with groupJson: $groupJson")
                                        val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                        val url = MainRoutes.ResticBackupDetail.getRoute(groupJsonEncoded = encodedJson)
                                        Log.d("ResticRestorePage", "Full URL: $url")
                                        navController.navigateSingle(url)
                                    },
                                    context = LocalContext.current,
                                    accountId = "local",
                                    iconVersion = iconVersion            // 新增：透传图标版本
                                )
                            }
                        }
                    }
                }

                is ResticRestoreUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_load_failed))
                            BodyMediumText(text = currentState.message)
                            Button(onClick = { viewModel.loadBackedUpApps() }) {
                                Text(stringResource(R.string.restore_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ResticBackupGroupItem(
    group: ResticBackupGroup,
    onClick: () -> Unit,
    context: Context,
    accountId: String? = null,
    iconVersion: Int = 0,                // 默认 0，兼容其它调用点
    selectable: Boolean = true,          // 是否可勾选（无 config 则不可）
    selected: Boolean = false,           // 当前是否已选
    onSelectedChange: (Boolean) -> Unit = {}   // 勾选切换回调
) {
    val hasConfigSnapshot = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }

    Surface(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick     // 仅保留：点击整行导航到详情页
        )
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            PackageIconImage(
                packageName = group.packageName,
                size = SizeTokens.Level32,
                accountId = accountId,
                iconVersion = iconVersion         // 新增：透传，触发解压后重新取图
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TitleLargeText(
                        text = group.appLabel,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
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
                    if (!hasConfigSnapshot) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.restore_backup_incomplete),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                BodyMediumText(
                    text = group.packageName,
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
                )

                BodyMediumText(
                    text = DateUtil.formatTimestamp(
                        group.timestamp,
                        DateUtil.PATTERN_YMD_HMS
                    ),
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
                )

                Text(
                    text = stringResource(R.string.restore_snapshot_count, group.snapshotCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 复选框常驻右侧，前置分隔线，与备份页 ListItems.kt 一致
            VerticalDivider(
                modifier = Modifier.height(SizeTokens.Level32)
            )
            Checkbox(
                checked = selected && selectable,
                onCheckedChange = { checked ->
                    if (selectable) onSelectedChange(checked)
                },
                enabled = selectable    // 无 PACKAGE_CONFIG 时置灰不可勾选
            )
        }
    }
}