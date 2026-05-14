package com.catcatch.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
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
 * 任务列表项组件
 * 高信息密度设计，根据状态显示不同内容
 */
@Composable
fun TaskItem(
    task: DownloadTask,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenFolder: () -> Unit = {},
    onPlay: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 第一行：文件名 + 状态标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.outputName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(status = task.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 根据状态显示不同内容
            when (task.status) {
                TaskStatus.DOWNLOADING -> DownloadingInfo(task)
                TaskStatus.COMPLETED -> CompletedInfo(task)
                TaskStatus.FAILED -> FailedInfo(task)
                TaskStatus.MERGING -> MergingInfo(task)
                else -> {}
            }

            // 操作按钮行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    TaskStatus.DOWNLOADING, TaskStatus.PENDING -> {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "取消下载",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                        IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "重试下载",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    TaskStatus.COMPLETED -> {
                        IconButton(onClick = onOpenFolder, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "打开目录",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "播放",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {}
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除任务",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    Spacer(modifier = Modifier.height(4.dp))

    // 进度百分比 + 速度 + 剩余时间 + 分片信息
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
private fun CompletedInfo(task: DownloadTask) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (task.fileSize > 0) {
            Text(
                text = task.fileSizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (task.completedAt != null) {
            val timeText = formatTime(task.completedAt)
            Text(
                text = timeText,
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
    Text(
        text = "正在合并分片...",
        style = MaterialTheme.typography.bodySmall,
        color = StatusMerging
    )
}

/**
 * 格式化时间戳为可读时间
 */
private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
