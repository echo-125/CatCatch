package com.catcatch.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.components.CatCatchTopAppBar
import com.catcatch.ui.home.HomeViewModel
import com.catcatch.ui.task.TaskItem
import com.catcatch.ui.theme.StatusCompleted
import com.catcatch.ui.theme.StatusDownloading
import com.catcatch.ui.theme.StatusFailed

/**
 * 下载列表页面
 * 支持多选模式和批量操作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: HomeViewModel
) {
    val taskListState by viewModel.taskListState.collectAsState()
    val tasks = taskListState.tasks

    // 多选模式状态
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Long>()) }

    // 退出多选模式
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    // 切换选中状态
    fun toggleSelection(taskId: Long) {
        selectedIds = if (taskId in selectedIds) {
            selectedIds - taskId
        } else {
            selectedIds + taskId
        }
        // 如果没有选中项，退出多选模式
        if (selectedIds.isEmpty()) {
            isSelectionMode = false
        }
    }

    // 全选/取消全选
    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == tasks.size) {
            emptySet()
        } else {
            tasks.map { it.id }.toSet()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题栏 - 始终保持同一布局
        CatCatchTopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下载管理")
                    if (tasks.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CompactStats(tasks)
                    }
                }
            },
            actions = {
                if (isSelectionMode) {
                    // 多选模式下的按钮
                    TextButton(onClick = ::toggleSelectAll) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (selectedIds.size == tasks.size) "取消全选" else "全选")
                    }
                    IconButton(onClick = ::exitSelectionMode) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "退出多选"
                        )
                    }
                } else {
                    // 普通模式下的按钮
                    if (tasks.isNotEmpty()) {
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(
                                imageVector = Icons.Default.Checklist,
                                contentDescription = "多选"
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.clearFinished() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "清除已完成任务"
                        )
                    }
                }
            }
        )

        if (tasks.isEmpty()) {
            EmptyState()
        } else {
            // 主内容区
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { Spacer(modifier = Modifier.height(2.dp)) }
                items(
                    items = tasks,
                    key = { it.id }
                ) { task ->
                    TaskItem(
                        task = task,
                        isSelectionMode = isSelectionMode,
                        isSelected = task.id in selectedIds,
                        onDelete = { viewModel.deleteTask(task.id) },
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry = { viewModel.retryTask(task.id) },
                        onLongClick = {
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds = setOf(task.id)
                            }
                        },
                        onClick = { toggleSelection(task.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }
        }

        // 底部批量操作栏（多选模式下始终显示，未选中时提示用户选择）
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            BatchActionBar(
                selectedTasks = tasks.filter { it.id in selectedIds },
                onBatchRetry = {
                    viewModel.batchRetry(selectedIds)
                    exitSelectionMode()
                },
                onBatchDelete = {
                    viewModel.batchDelete(selectedIds)
                    exitSelectionMode()
                }
            )
        }
    }
}

/**
 * 底部批量操作栏
 */
@Composable
private fun BatchActionBar(
    selectedTasks: List<DownloadTask>,
    onBatchRetry: () -> Unit,
    onBatchDelete: () -> Unit
) {
    // 判断是否所有选中任务都是可重试状态
    val canRetry = selectedTasks.isNotEmpty() && selectedTasks.all {
        it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.PENDING)
    }
    val hasSelection = selectedTasks.isNotEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasSelection) "已选 ${selectedTasks.size} 项" else "请选择任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            if (canRetry) {
                TextButton(onClick = onBatchRetry) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("批量开始")
                }
            }

            TextButton(
                onClick = onBatchDelete,
                enabled = hasSelection
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "批量删除",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 紧凑统计信息
 */
@Composable
private fun CompactStats(tasks: List<DownloadTask>) {
    val downloading = tasks.count { it.status == TaskStatus.DOWNLOADING }
    val completed = tasks.count { it.status == TaskStatus.COMPLETED }
    val failed = tasks.count { it.status == TaskStatus.FAILED }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (downloading > 0) {
            StatusDot(StatusDownloading)
            Text(
                text = "$downloading",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (completed > 0) {
            StatusDot(StatusCompleted)
            Text(
                text = "$completed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (failed > 0) {
            StatusDot(StatusFailed)
            Text(
                text = "$failed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 状态小圆点
 */
@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 空状态
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Download,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "暂无下载任务",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "在主页添加 M3U8 链接开始下载",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
