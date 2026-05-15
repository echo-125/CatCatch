package com.catcatch.ui.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.components.StatusChip
import com.catcatch.ui.theme.StatusCancelled
import com.catcatch.ui.theme.StatusCompleted
import com.catcatch.ui.theme.StatusDownloading
import com.catcatch.ui.theme.StatusFailed
import com.catcatch.ui.theme.StatusMerging
import com.catcatch.ui.theme.StatusPending

/**
 * 获取状态对应的颜色
 */
private fun getStatusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.PENDING -> StatusPending
    TaskStatus.DOWNLOADING -> StatusDownloading
    TaskStatus.MERGING -> StatusMerging
    TaskStatus.TRANSCODING -> StatusMerging
    TaskStatus.COMPLETED -> StatusCompleted
    TaskStatus.FAILED -> StatusFailed
    TaskStatus.CANCELLED -> StatusCancelled
}

/**
 * 任务列表项组件
 * 支持多选模式，左侧状态色条，高信息密度设计
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskItem(
    task: DownloadTask,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusColor = getStatusColor(task.status)
    val cardColor = animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "cardColor"
    )

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onClick() else {}
                },
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row {
            // 左侧状态色条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(IntrinsicSize.Max)
                    .background(statusColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // 第一行：文件名 + 状态标签/Checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onClick() }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Text(
                        text = task.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (!isSelectionMode) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip(status = task.status)
                    }
                }

                // 第二行：状态信息 + 操作按钮（同一行）
                if (!isSelectionMode) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：状态特定信息
                        Box(modifier = Modifier.weight(1f)) {
                            when (task.status) {
                                TaskStatus.DOWNLOADING -> DownloadingInfo(task)
                                TaskStatus.COMPLETED -> CompletedInfo(task)
                                TaskStatus.FAILED -> FailedInfo(task)
                                TaskStatus.MERGING, TaskStatus.TRANSCODING -> MergingInfo(task)
                                else -> {}
                            }
                        }

                        // 右侧：操作按钮
                        ActionBar(
                            task = task,
                            onCancel = onCancel,
                            onRetry = onRetry,
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
    }
}

/**
 * 操作按钮行（紧凑布局）
 */
@Composable
private fun ActionBar(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (task.status) {
            TaskStatus.DOWNLOADING, TaskStatus.PENDING -> {
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "取消",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重试",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            else -> {}
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 下载中状态信息（紧凑布局）
 */
@Composable
private fun DownloadingInfo(task: DownloadTask) {
    Column {
        // 进度条
        LinearProgressIndicator(
            progress = { task.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = StatusDownloading,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(2.dp))

        // 进度信息行
        val infoParts = mutableListOf<String>()
        infoParts.add(task.progressText)
        if (task.speedText.isNotEmpty()) infoParts.add(task.speedText)
        if (task.durationText.isNotEmpty()) infoParts.add(task.durationText)
        if (task.total > 0) infoParts.add("${task.downloaded}/${task.total}")
        Text(
            text = infoParts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 已完成状态信息（紧凑布局）
 */
@Composable
private fun CompletedInfo(task: DownloadTask) {
    val infoParts = mutableListOf<String>()
    if (task.resolutionText.isNotEmpty()) infoParts.add(task.resolutionText)
    if (task.durationText.isNotEmpty()) infoParts.add(task.durationText)
    if (task.fileSize > 0) infoParts.add(task.fileSizeText)
    Text(
        text = infoParts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

/**
 * 失败状态信息（紧凑布局）
 */
@Composable
private fun FailedInfo(task: DownloadTask) {
    Text(
        text = task.message.ifEmpty { "下载失败" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 合并中状态信息（紧凑布局）
 */
@Composable
private fun MergingInfo(task: DownloadTask) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = StatusMerging
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = task.message.ifEmpty { "合并中..." },
            style = MaterialTheme.typography.bodySmall,
            color = StatusMerging,
            maxLines = 1
        )
    }
}

/**
 * 格式化时间戳为可读时间
 */
private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
