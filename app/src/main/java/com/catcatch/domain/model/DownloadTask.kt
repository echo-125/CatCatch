package com.catcatch.domain.model

import androidx.compose.runtime.Stable

/**
 * 下载任务数据模型
 */
@Stable
data class DownloadTask(
    val id: Long = 0,
    val url: String,
    val outputName: String,
    val outputDir: String,
    val headers: Map<String, String> = emptyMap(),
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0f,
    val downloaded: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val fileSize: Long = 0,
    val speed: Long = 0, // bytes per second
    val outputPath: String = ""
) {
    /**
     * 进度百分比文本
     */
    val progressText: String
        get() = "${(progress * 100).toInt()}%"

    /**
     * 状态描述文本
     */
    val statusText: String
        get() = when (status) {
            TaskStatus.PENDING -> "等待中"
            TaskStatus.DOWNLOADING -> "下载中"
            TaskStatus.MERGING -> "合并中"
            TaskStatus.TRANSCODING -> "转码中"
            TaskStatus.COMPLETED -> "已完成"
            TaskStatus.FAILED -> "已失败"
            TaskStatus.CANCELLED -> "已取消"
        }

    /**
     * 格式化的文件大小
     */
    val fileSizeText: String
        get() = formatFileSize(fileSize)

    /**
     * 格式化的下载速度
     */
    val speedText: String
        get() = if (speed > 0) "${formatFileSize(speed)}/s" else ""

    /**
     * 预估剩余时间
     */
    val remainingTimeText: String
        get() {
            if (speed <= 0 || progress >= 1f) return ""
            val remainingBytes = ((1 - progress) * fileSize).toLong()
            val remainingSeconds = remainingBytes / speed
            return when {
                remainingSeconds < 60 -> "${remainingSeconds}秒"
                remainingSeconds < 3600 -> "${remainingSeconds / 60}分钟"
                else -> "${remainingSeconds / 3600}小时"
            }
        }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
