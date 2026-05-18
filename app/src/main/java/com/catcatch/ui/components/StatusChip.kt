package com.catcatch.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.theme.StatusCancelled
import com.catcatch.ui.theme.StatusCompleted
import com.catcatch.ui.theme.StatusDownloading
import com.catcatch.ui.theme.StatusFailed
import com.catcatch.ui.theme.StatusMerging
import com.catcatch.ui.theme.StatusPending

/**
 * 任务状态标签
 * 显示彩色圆角标签表示任务状态
 */
@Composable
fun StatusChip(
    status: TaskStatus,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, textColor) = getStatusColors(status)
    val text = getStatusText(status)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun getStatusColors(status: TaskStatus): Pair<Color, Color> {
    return when (status) {
        TaskStatus.PENDING -> StatusPending.copy(alpha = 0.2f) to StatusPending
        TaskStatus.DOWNLOADING -> StatusDownloading.copy(alpha = 0.2f) to StatusDownloading
        TaskStatus.MERGING -> StatusMerging.copy(alpha = 0.2f) to StatusMerging
        TaskStatus.TRANSCODING -> StatusMerging.copy(alpha = 0.2f) to StatusMerging
        TaskStatus.COMPLETED -> StatusCompleted.copy(alpha = 0.2f) to StatusCompleted
        TaskStatus.FAILED -> StatusFailed.copy(alpha = 0.2f) to StatusFailed
        TaskStatus.CANCELLED -> StatusCancelled.copy(alpha = 0.2f) to StatusCancelled
    }
}

private fun getStatusText(status: TaskStatus): String {
    return when (status) {
        TaskStatus.PENDING -> "等待中"
        TaskStatus.DOWNLOADING -> "下载中"
        TaskStatus.MERGING -> "合并中"
        TaskStatus.TRANSCODING -> "转码中"
        TaskStatus.COMPLETED -> "已完成"
        TaskStatus.FAILED -> "失败"
        TaskStatus.CANCELLED -> "已取消"
    }
}
