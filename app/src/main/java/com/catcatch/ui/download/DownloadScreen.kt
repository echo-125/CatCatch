package com.catcatch.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.components.CatCatchTopAppBar
import com.catcatch.ui.home.HomeViewModel
import com.catcatch.ui.task.TaskItem
import com.catcatch.ui.theme.StatusCompleted
import com.catcatch.ui.theme.StatusDownloading
import com.catcatch.ui.theme.StatusFailed
import com.catcatch.ui.theme.StatusPending

/**
 * 下载列表页面
 * 支持横屏双面板布局
 */
@Composable
fun DownloadScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val taskListState by viewModel.taskListState.collectAsState()
    val tasks = taskListState.tasks
    var selectedTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedTask = tasks.find { it.id == selectedTaskId }

    Column(modifier = Modifier.fillMaxSize()) {
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
                IconButton(onClick = { viewModel.clearFinished() }) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "清除已完成任务"
                    )
                }
            }
        )

        if (tasks.isEmpty()) {
            // 空状态
            EmptyState()
        } else {
            // 主内容区
            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧任务列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    items(
                        items = tasks,
                        key = { it.id }
                    ) { task ->
                        TaskItem(
                            task = task,
                            onDelete = { viewModel.deleteTask(task.id) },
                            onCancel = { viewModel.cancelTask(task.id) },
                            onRetry = { viewModel.retryTask(task.id) },
                            onOpenFolder = { /* TODO: 打开目录 */ },
                            onPlay = { /* TODO: 播放视频 */ }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }

                // 右侧详情面板（仅在有选中任务时显示）
                if (selectedTask != null) {
                    TaskDetailPanel(
                        task = selectedTask,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 紧凑统计信息（标题右侧副标题形式）
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

/**
 * 任务详情面板（横屏双面板右侧）
 */
@Composable
private fun TaskDetailPanel(
    task: DownloadTask,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "任务详情",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            DetailItem("文件名", task.outputName)
            DetailItem("状态", task.statusText)
            DetailItem("URL", task.url)

            if (task.fileSize > 0) {
                DetailItem("文件大小", task.fileSizeText)
            }

            if (task.status == TaskStatus.DOWNLOADING) {
                DetailItem("进度", task.progressText)
                if (task.speedText.isNotEmpty()) {
                    DetailItem("速度", task.speedText)
                }
                if (task.remainingTimeText.isNotEmpty()) {
                    DetailItem("剩余时间", task.remainingTimeText)
                }
                if (task.total > 0) {
                    DetailItem("分片", "${task.downloaded}/${task.total}")
                }
            }

            if (task.status == TaskStatus.FAILED && task.message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "错误信息",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (task.outputPath.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                DetailItem("输出路径", task.outputPath)
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )
    }
}
