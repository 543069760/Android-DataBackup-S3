package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.platform.LocalContext
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.restore.ResticBackupGroup
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.LocalContentColor
import com.xayah.feature.main.restore.RestoreScaffold
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.LabelLargeText
import com.xayah.core.ui.component.LabelMediumText
import com.xayah.core.ui.component.TextButton
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.encodeAccountId
import com.xayah.core.util.decodeURL
import com.xayah.feature.main.restore.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CloudRestorePage(
    navController: NavController,
    accountName: String,
    viewModel: CloudRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val iconVersion by viewModel.iconVersion.collectAsStateWithLifecycle()
    // accountName 在整个 Composable 内是常量，去前缀 + sanitize 只算一次，
    val accountId = encodeAccountId(accountName.replace("accountName=", "").decodeURL())

    LaunchedEffect(accountName) {
        viewModel.setCloudEntity(accountName)
    }

    val needsRefresh = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("cloud_needs_refresh", false)
        ?.collectAsStateWithLifecycle()

    LaunchedEffect(needsRefresh?.value) {
        if (needsRefresh?.value == true) {
            viewModel.forceReload()
            navController.currentBackStackEntry
                ?.savedStateHandle
                ?.set("cloud_needs_refresh", false)
        }
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = stringResource(R.string.restore_cloud_restic_restore_title)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            val currentState = uiState
            when (currentState) {
                is CloudRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is CloudRestoreUiState.Success -> {
                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_no_cloud_backup))
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            items(
                                currentState.groups,
                                key = { item: ResticBackupGroup -> "${item.userId}-${item.packageName}-${item.timestamp}" }
                            ) { group: ResticBackupGroup ->
                                ResticBackupGroupItem(
                                    group = group,
                                    onClick = {
                                        try {
                                            Log.d("CloudRestorePage", "开始处理点击事件")

                                            Log.d(
                                                "CloudRestorePage",
                                                "备份项被点击: ${group.packageName}"
                                            )

                                            // 1. JSON 序列化 group 对象
                                            val groupJson = Json.encodeToString(group)
                                            val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                            Log.d(
                                                "CloudRestorePage",
                                                "JSON序列化成功: ${encodedJson.take(100)}..."
                                            )

                                            // 2. URL 编码账户名称
                                            val cleanAccountName = accountName.replace("accountName=", "")
                                            val encodedAccountName = URLEncoder.encode(cleanAccountName, "UTF-8")
                                            Log.d(
                                                "CloudRestorePage",
                                                "账户名编码: $encodedAccountName"
                                            )

                                            // 3. 构建导航路由
                                            val url = MainRoutes.CloudBackupDetail.getRoute(
                                                encodedJson,
                                                encodedAccountName
                                            )
                                            Log.d("CloudRestorePage", "构建路由: $url")

                                            // 4. 执行导航
                                            navController.navigateSingle(url)
                                            Log.d("CloudRestorePage", "导航调用完成")
                                        } catch (e: Exception) {
                                            Log.e("CloudRestorePage", "点击事件处理失败", e)
                                        }
                                    },
                                    context = LocalContext.current,
                                    accountId = accountId,
                                    iconVersion = iconVersion
                                )
                            }
                        }
                    }
                }

                is CloudRestoreUiState.Error -> {
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
                            Button(onClick = { viewModel.setCloudEntity(accountName) }) {
                                Text(stringResource(R.string.restore_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}