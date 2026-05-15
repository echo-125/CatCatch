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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.components.ExpandableSection
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
    onOpenFolder: () -> Unit = {},
    onPlay: () -> Unit = {},
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
                    .padding(12.dp)
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
                        text = task.outputName,
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

                Spacer(modifier = Modifier.height(8.dp))

                // 根据状态显示不同内容
                when (task.status) {
                    TaskStatus.DOWNLOADING -> DownloadingInfo(task)
                    TaskStatus.COMPLETED -> CompletedInfo(task, isSelectionMode)
                    TaskStatus.FAILED -> FailedInfo(task)
                    TaskStatus.MERGING, TaskStatus.TRANSCODING -> MergingInfo(task)
                    else -> {}
                }

                // 操作按钮行（多选模式下隐藏）
                if (!isSelectionMode) {
                    ActionBar(
                        task = task,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onOpenFolder = onOpenFolder,
                        onPlay = onPlay
                    )
                }
            }
        }
    }
}

/**
 * 操作按钮行
 */
@Composable
private fun ActionBar(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (task.status) {
            TaskStatus.DOWNLOADING, TaskStatus.PENDING -> {
                TextButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "取消",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                TextButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "重试",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            TaskStatus.COMPLETED -> {
                TextButton(onClick = onOpenFolder) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "目录",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "播放",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            else -> {}
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 下载中状态信息
 */
@Composable
private fun DownloadingInfo(task: DownloadTask) {
    // 进度条
    LinearProgressIndicator(
        progress = { task.progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = StatusDownloading,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Spacer(modifier = Modifier.height(4.dp))

    // 进度百分比 + 速度 + 剩余时间
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${task.progressText} ${task.speedText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (task.remainingTimeText.isNotEmpty()) {
            Text(
                text = "剩余 ${task.remainingTimeText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 分片信息
    if (task.total > 0) {
        Text(
            text = "分片: ${task.downloaded}/${task.total}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 已完成状态信息
 */
@Composable
private fun CompletedInfo(task: DownloadTask, isSelectionMode: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选模式下不显示勾选图标，用复选框代替
        if (!isSelectionMode) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = StatusCompleted
            )
        }
        if (task.fileSize > 0) {
            Text(
                text = task.fileSizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (task.completedAt != null) {
            Text(
                text = formatTime(task.completedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 失败状态信息
 */
@Composable
private fun FailedInfo(task: DownloadTask) {
    if (task.message.isNotEmpty()) {
        ExpandableSection(title = "下载失败 - 点击查看详情") {
            Text(
                text = task.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 合并中状态信息
 */
@Composable
private fun MergingInfo(task: DownloadTask) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = StatusMerging
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "正在合并分片...",
            style = MaterialTheme.typography.bodySmall,
            color = StatusMerging
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
