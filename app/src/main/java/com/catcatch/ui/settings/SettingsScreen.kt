package com.catcatch.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.catcatch.ui.components.CatCatchTopAppBar

/**
 * 设置页面
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    var showDownloadDirDialog by remember { mutableStateOf(false) }
    var showConcurrentTasksDialog by remember { mutableStateOf(false) }
    var showConcurrentSegmentsDialog by remember { mutableStateOf(false) }
    var showDarkModeDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        CatCatchTopAppBar(title = "设置")

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 下载设置
            item {
                SettingsSection(title = "下载设置") {
                    SettingsItem(
                        icon = Icons.Default.Folder,
                        title = "下载目录",
                        subtitle = state.downloadDir,
                        onClick = { showDownloadDirDialog = true }
                    )
                    SettingsItem(
                        icon = Icons.Default.Speed,
                        title = "最大并发任务数",
                        subtitle = "${state.maxConcurrentTasks}",
                        onClick = { showConcurrentTasksDialog = true }
                    )
                    SettingsItem(
                        icon = Icons.Default.Code,
                        title = "每任务并发分片数",
                        subtitle = "${state.maxConcurrentSegments}",
                        onClick = { showConcurrentSegmentsDialog = true }
                    )
                }
            }

            // 浏览器插件
            item {
                SettingsSection(title = "浏览器插件") {
                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = "URL Scheme",
                        subtitle = "catcatch://add",
                        onClick = {
                            clipboardManager.setText(
                                AnnotatedString("catcatch://add?url=&title=&headers=&referer=")
                            )
                        }
                    )
                }
            }

            // 外观
            item {
                SettingsSection(title = "外观") {
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        title = "深色模式",
                        subtitle = darkModeLabel(state.darkMode),
                        onClick = { showDarkModeDialog = true }
                    )
                }
            }

            // 关于
            item {
                SettingsSection(title = "关于") {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "版本",
                        subtitle = "1.0.0",
                        onClick = { }
                    )
                }
            }
        }
    }

    // 下载目录对话框
    if (showDownloadDirDialog) {
        var dirText by remember { mutableStateOf(state.downloadDir) }
        AlertDialog(
            onDismissRequest = { showDownloadDirDialog = false },
            title = { Text("下载目录") },
            text = {
                OutlinedTextField(
                    value = dirText,
                    onValueChange = { dirText = it },
                    label = { Text("路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dirText.isNotBlank()) {
                        viewModel.updateDownloadDir(dirText.trim())
                    }
                    showDownloadDirDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDirDialog = false }) { Text("取消") }
            }
        )
    }

    // 最大并发任务数对话框
    if (showConcurrentTasksDialog) {
        val options = listOf(1, 2, 3, 5)
        var selected by remember { mutableIntStateOf(state.maxConcurrentTasks) }
        AlertDialog(
            onDismissRequest = { showConcurrentTasksDialog = false },
            title = { Text("最大并发任务数") },
            text = {
                Column {
                    options.forEach { value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == value,
                                    onClick = { selected = value }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == value,
                                onClick = { selected = value }
                            )
                            Text(
                                text = "$value",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateMaxConcurrentTasks(selected)
                    showConcurrentTasksDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showConcurrentTasksDialog = false }) { Text("取消") }
            }
        )
    }

    // 每任务并发分片数对话框
    if (showConcurrentSegmentsDialog) {
        val options = listOf(4, 8, 16, 32)
        var selected by remember { mutableIntStateOf(state.maxConcurrentSegments) }
        AlertDialog(
            onDismissRequest = { showConcurrentSegmentsDialog = false },
            title = { Text("每任务并发分片数") },
            text = {
                Column {
                    options.forEach { value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == value,
                                    onClick = { selected = value }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == value,
                                onClick = { selected = value }
                            )
                            Text(
                                text = "$value",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateMaxConcurrentSegments(selected)
                    showConcurrentSegmentsDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showConcurrentSegmentsDialog = false }) { Text("取消") }
            }
        )
    }

    // 深色模式对话框
    if (showDarkModeDialog) {
        val options = listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色")
        var selected by remember { mutableIntStateOf(state.darkMode) }
        AlertDialog(
            onDismissRequest = { showDarkModeDialog = false },
            title = { Text("深色模式") },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == value,
                                    onClick = { selected = value }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == value,
                                onClick = { selected = value }
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateDarkMode(selected)
                    showDarkModeDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDarkModeDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun darkModeLabel(mode: Int): String = when (mode) {
    1 -> "浅色"
    2 -> "深色"
    else -> "跟随系统"
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
