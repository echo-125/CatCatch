package com.catcatch.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.catcatch.ui.components.CatCatchTopAppBar

/**
 * 从 SAF URI 提取显示路径
 */
private fun getDisplayPathFromUri(uri: Uri): String {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    if (docId.startsWith("primary:")) {
        return "/storage/emulated/0/${docId.removePrefix("primary:")}"
    }
    if (docId.contains(":")) {
        val parts = docId.split(":")
        if (parts.size == 2) {
            return "/storage/${parts[0]}/${parts[1]}"
        }
    }
    return uri.path ?: uri.toString()
}

/**
 * 设置页面
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showDirInputDialog by remember { mutableStateOf(false) }
    var showConcurrentTasksDialog by remember { mutableStateOf(false) }
    var showConcurrentSegmentsDialog by remember { mutableStateOf(false) }
    var showDarkModeDialog by remember { mutableStateOf(false) }
    var showTranscodeModeDialog by remember { mutableStateOf(false) }
    var showDirMethodDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // 监听事件
    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is SettingsEvent.ShowMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // SAF 目录选择器
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 获取持久化权限
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
            } catch (e: Exception) {
                android.util.Log.e("SettingsScreen", "获取持久化权限失败", e)
            }
            val displayPath = getDisplayPathFromUri(it)
            viewModel.updateDownloadDirFromSaf(displayPath, it.toString())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CatCatchTopAppBar(title = "设置")

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 下载设置
            item {
                SettingsSection(title = "下载设置") {
                    // 目录选择方式
                    SettingsItem(
                        icon = Icons.Default.SwapHoriz,
                        title = "目录选择方式",
                        subtitle = if (state.useDirPicker) "目录选择器" else "手动输入路径",
                        onClick = { showDirMethodDialog = true }
                    )
                    // 下载目录
                    SettingsItem(
                        icon = Icons.Default.Folder,
                        title = "下载目录",
                        subtitle = state.downloadDir,
                        onClick = {
                            if (state.useDirPicker) {
                                // 直接启动 SAF
                                dirPickerLauncher.launch(null)
                            } else {
                                // 弹出输入对话框
                                showDirInputDialog = true
                            }
                        }
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
                    SettingsItem(
                        icon = Icons.Default.VideoSettings,
                        title = "转码模式",
                        subtitle = transcodeModeLabel(state.transcodeMode),
                        onClick = { showTranscodeModeDialog = true }
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
                                AnnotatedString("""catcatch://add?url=&title=&headers={"origin":"","referer":""}""")
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

    // 目录选择方式对话框
    if (showDirMethodDialog) {
        val options = listOf(true to "目录选择器", false to "手动输入路径")
        var selected by remember { mutableStateOf(state.useDirPicker) }
        AlertDialog(
            onDismissRequest = { showDirMethodDialog = false },
            title = { Text("目录选择方式") },
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
                    viewModel.updateUseDirPicker(selected)
                    showDirMethodDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDirMethodDialog = false }) { Text("取消") }
            }
        )
    }

    // 手动输入路径对话框
    if (showDirInputDialog) {
        var dirText by remember { mutableStateOf(state.downloadDir) }
        var pathError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showDirInputDialog = false },
            title = { Text("下载目录") },
            text = {
                Column {
                    Text(
                        text = "当前: ${state.downloadDir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    OutlinedTextField(
                        value = dirText,
                        onValueChange = {
                            dirText = it
                            pathError = null
                        },
                        label = { Text("目录路径") },
                        placeholder = { Text("/storage/emulated/0/Download/CatCatch") },
                        singleLine = true,
                        isError = pathError != null,
                        supportingText = pathError?.let { { Text(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    )

                    Text(
                        text = "仅支持内部存储路径:\n• /storage/emulated/0/...\n\n如需使用 SD 卡，请选择「目录选择器」方式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = dirText.trim()
                    if (trimmed.isEmpty()) {
                        pathError = "路径不能为空"
                    } else if (!viewModel.isValidDirPath(trimmed)) {
                        pathError = "路径格式无效，仅支持 /storage/emulated/0/"
                    } else {
                        viewModel.updateDownloadDir(trimmed)
                        showDirInputDialog = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDirInputDialog = false }) { Text("取消") }
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

    // 转码模式对话框
    if (showTranscodeModeDialog) {
        val options = listOf(
            0 to "自动（推荐）",
            1 to "FFmpeg-kit",
            2 to "系统原生"
        )
        var selected by remember { mutableIntStateOf(state.transcodeMode) }
        AlertDialog(
            onDismissRequest = { showTranscodeModeDialog = false },
            title = { Text("转码模式") },
            text = {
                Column {
                    Text(
                        text = "选择 TS→MP4 转码方式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
                    viewModel.updateTranscodeMode(selected)
                    showTranscodeModeDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTranscodeModeDialog = false }) { Text("取消") }
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

private fun transcodeModeLabel(mode: Int): String = when (mode) {
    1 -> "FFmpeg-kit"
    2 -> "系统原生"
    else -> "自动（推荐）"
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
