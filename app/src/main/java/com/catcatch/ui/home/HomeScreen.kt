package com.catcatch.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.catcatch.ui.components.ExpandableSection

/**
 * 主页 — 添加下载任务
 * 使用 WindowSizeClass 自适应布局
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel
) {
    val inputState by viewModel.inputState.collectAsState()
    val uiEvent by viewModel.uiEventState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 错误消息
    LaunchedEffect(uiEvent.error) {
        uiEvent.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 成功消息
    LaunchedEffect(uiEvent.success) {
        uiEvent.success?.let { success ->
            snackbarHostState.showSnackbar(
                message = success,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // 内容区域 - 使用 Box 实现真正的居中
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .widthIn(max = 600.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AddTaskCard(
                    url = inputState.url,
                    fileName = inputState.fileName,
                    headers = inputState.headers,
                    isAdding = uiEvent.isAdding,
                    onUrlChange = viewModel::onUrlChange,
                    onFileNameChange = viewModel::onFileNameChange,
                    onHeadersChange = viewModel::onHeadersChange,
                    onAddTask = viewModel::addTask,
                    onBatchAdd = viewModel::showBatchDialog,
                    onSmartPaste = viewModel::smartPaste
                )
            }
        }

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(16.dp)
        )
    }

    // 批量添加对话框
    if (uiEvent.showBatchDialog) {
        BatchAddDialog(
            batchText = uiEvent.batchText,
            isAdding = uiEvent.isAdding,
            onTextChange = viewModel::onBatchTextChange,
            onConfirm = viewModel::addBatchTasks,
            onDismiss = viewModel::hideBatchDialog
        )
    }
}

/**
 * 添加任务卡片
 * 支持小窗口极简模式（高度 < 400dp）
 */
@Composable
private fun AddTaskCard(
    url: String,
    fileName: String,
    headers: String,
    isAdding: Boolean,
    onUrlChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
    onHeadersChange: (String) -> Unit,
    onAddTask: () -> Unit,
    onBatchAdd: () -> Unit,
    onSmartPaste: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val inputShape = RoundedCornerShape(12.dp)

    BoxWithConstraints {
        val isCompactHeight = maxHeight < 400.dp

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape
        ) {
            Column(
                modifier = Modifier.padding(if (isCompactHeight) 12.dp else 20.dp)
            ) {
                if (!isCompactHeight) {
                    Text(
                        text = "添加下载任务",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("M3U8 链接") },
                    placeholder = { Text("https://example.com/video.m3u8") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    enabled = !isAdding,
                    shape = inputShape,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let { onUrlChange(it) }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "粘贴 URL"
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 智能粘贴按钮
                OutlinedButton(
                    onClick = {
                        clipboardManager.getText()?.text?.let { onSmartPaste(it) }
                    },
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth(),
                    shape = buttonShape
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("智能粘贴")
                }

                if (isCompactHeight) {
                    // 小窗口极简模式：只显示 URL 和开始按钮
                    Spacer(modifier = Modifier.height(8.dp))

                    // 高级选项收入折叠区域
                    ExpandableSection(title = "高级选项") {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = onFileNameChange,
                            label = { Text("文件名（可选）") },
                            placeholder = { Text("自动生成") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isAdding,
                            shape = inputShape
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = headers,
                            onValueChange = onHeadersChange,
                            label = { Text("请求头（可选）") },
                            placeholder = { Text("""{"origin":"https://example.com","referer":"https://example.com"}""") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            enabled = !isAdding,
                            shape = inputShape
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onBatchAdd,
                            enabled = !isAdding,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            shape = buttonShape
                        ) {
                            Text(text = "批量添加")
                        }
                    }
                } else {
                    // 正常模式：显示所有字段
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = fileName,
                        onValueChange = onFileNameChange,
                        label = { Text("文件名（可选）") },
                        placeholder = { Text("自动生成") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isAdding,
                        shape = inputShape
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = headers,
                        onValueChange = onHeadersChange,
                        label = { Text("请求头（可选）") },
                        placeholder = { Text("""{"origin":"https://example.com","referer":"https://example.com"}""") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !isAdding,
                        shape = inputShape
                    )
                }

                Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 20.dp))

                Button(
                    onClick = onAddTask,
                    enabled = !isAdding && url.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = if (isAdding) "添加中..." else "开始下载")
                }

                if (!isCompactHeight) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onBatchAdd,
                        enabled = !isAdding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = buttonShape
                    ) {
                        Text(text = "批量添加")
                    }
                }
            }
        }
    }
}

/**
 * 批量添加对话框
 */
@Composable
private fun BatchAddDialog(
    batchText: String,
    isAdding: Boolean,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        title = { Text("批量添加任务") },
        text = {
            Column {
                Text(
                    text = "每行一个任务，格式：链接|文件名|请求头JSON",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = batchText,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text("https://example.com/video1.m3u8\nhttps://example.com/video2.m3u8|我的视频")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    enabled = !isAdding,
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isAdding && batchText.isNotBlank()
            ) {
                Text(text = if (isAdding) "添加中..." else "添加")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAdding
            ) {
                Text("取消")
            }
        }
    )
}
