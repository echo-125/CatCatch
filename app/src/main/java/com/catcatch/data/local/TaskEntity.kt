package com.catcatch.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.catcatch.domain.model.TaskStatus

/**
 * 下载任务数据库实体
 */
@Entity(tableName = "tasks", indices = [Index(value = ["status"])])
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
    val createdAt: Long = System.currentTimeMillis(),
    val duration: Double = 0.0,
    val resolution: String = "",
    val fileSize: Long = 0,
    val savedPath: String = ""  // 文件实际保存路径
)
