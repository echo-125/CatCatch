package com.catcatch.domain.model

/**
 * 下载任务状态枚举
 */
enum class TaskStatus {
    /** 等待中 */
    PENDING,
    /** 下载中 */
    DOWNLOADING,
    /** 合并中 */
    MERGING,
    /** 转码中 */
    TRANSCODING,
    /** 已完成 */
    COMPLETED,
    /** 已失败 */
    FAILED,
    /** 已取消 */
    CANCELLED
}
