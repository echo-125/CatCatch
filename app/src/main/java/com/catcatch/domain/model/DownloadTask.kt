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
    val createdAt: Long = System.currentTimeMillis()
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
            TaskStatus.COMPLETED -> "已完成"
            TaskStatus.FAILED -> "已失败"
            TaskStatus.CANCELLED -> "已取消"
        }
}
