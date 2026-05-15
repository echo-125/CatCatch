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
    val outputPath: String = "",
    val duration: Double = 0.0,
    val resolution: String = "",
    val savedPath: String = ""  // 文件实际保存路径
) {
    /**
     * 显示的文件名（带后缀）
     * 根据任务状态和文件类型自动添加后缀
     */
    val displayName: String
        get() {
            // 如果有 savedPath，从路径中提取文件名（包含后缀）
            if (savedPath.isNotEmpty()) {
                val fileName = savedPath.substringAfterLast("/")
                if (fileName.isNotEmpty()) return fileName
            }

            // 根据状态判断后缀
            val suffix = when (status) {
                TaskStatus.COMPLETED -> {
                    // 已完成状态，检查是否转码成功（通过 message 判断）
                    if (message.contains("转码完成")) ".mp4" else ".ts"
                }
                TaskStatus.TRANSCODING -> ".mp4"  // 转码中，最终会是 mp4
                else -> ".ts"  // 其他状态，当前是 ts 文件
            }
            return "$outputName$suffix"
        }

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
     * 格式化的视频时长
     */
    val durationText: String
        get() {
            if (duration <= 0) return ""
            val totalSeconds = duration.toLong()
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }

    /**
     * 格式化的分辨率
     */
    val resolutionText: String
        get() = resolution

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
