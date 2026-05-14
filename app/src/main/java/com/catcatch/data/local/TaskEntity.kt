package com.catcatch.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.catcatch.domain.model.TaskStatus

/**
 * 下载任务数据库实体
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val outputName: String,
    val outputDir: String,
    val headers: String = "",
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0f,
    val downloaded: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
