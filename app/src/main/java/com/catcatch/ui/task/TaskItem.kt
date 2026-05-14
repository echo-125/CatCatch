package com.catcatch.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.TaskStatus

/**
 * 任务列表项组件
 */
@Composable
fun TaskItem(
    task: DownloadTask,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 文件名和状态图标
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件名
                Text(
                    text = task.outputName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 状态图标
                StatusIcon(status = task.status)

                // 操作按钮
                Row {
                    // 取消按钮（下载中/等待中显示）
                    if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PENDING) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "取消下载",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // 重试按钮（失败/取消状态显示）
                    if (task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "重试下载",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 删除按钮
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "删除任务"
                        )
                    }
                }
            }

            // 状态文本
            Text(
                text = task.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = getStatusColor(task.status)
            )

            // 进度条（仅下载中显示）
            if (task.status == TaskStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                // 进度百分比
                Text(
                    text = task.progressText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 分片信息（仅下载中显示）
            if (task.status == TaskStatus.DOWNLOADING && task.total > 0) {
                Text(
                    text = "分片: ${task.downloaded}/${task.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 错误消息
            if (task.status == TaskStatus.FAILED && task.message.isNotEmpty()) {
                Text(
                    text = task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 状态图标
 */
@Composable
private fun StatusIcon(status: TaskStatus) {
    val (icon, tint) = when (status) {
        TaskStatus.PENDING -> Icons.Default.HourglassEmpty to Color.Gray
        TaskStatus.DOWNLOADING -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
        TaskStatus.MERGING -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.tertiary
        TaskStatus.COMPLETED -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        TaskStatus.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> Icons.Default.Pause to Color.Gray
    }

    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint
    )
}

/**
 * 状态颜色
 */
@Composable
private fun getStatusColor(status: TaskStatus): Color {
    return when (status) {
        TaskStatus.PENDING -> Color.Gray
        TaskStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
        TaskStatus.MERGING -> MaterialTheme.colorScheme.tertiary
        TaskStatus.COMPLETED -> Color(0xFF4CAF50)
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> Color.Gray
    }
}
