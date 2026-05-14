package com.catcatch.data.repository

import com.catcatch.data.local.TaskDao
import com.catcatch.data.local.TaskEntity
import com.catcatch.data.remote.M3U8Parser
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.M3U8Data
import com.catcatch.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 下载仓库
 * 封装数据层操作，提供领域层接口
 */
class DownloadRepository(
    private val taskDao: TaskDao,
    private val m3u8Parser: M3U8Parser
) {

    /**
     * 获取所有任务（Flow）
     */
    fun getAllTasks(): Flow<List<DownloadTask>> {
        return taskDao.getAllTasks().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * 根据 ID 获取任务
     */
    suspend fun getTaskById(id: Long): DownloadTask? {
        return taskDao.getTaskById(id)?.toDomain()
    }

    /**
     * 添加任务
     */
    suspend fun addTask(
        url: String,
        outputName: String,
        outputDir: String,
        headers: Map<String, String> = emptyMap()
    ): Long {
        val headersJson = if (headers.isEmpty()) "" else headers.entries.joinToString(",") { "${it.key}=${it.value}" }
        val entity = TaskEntity(
            url = url,
            outputName = outputName,
            outputDir = outputDir,
            headers = headersJson,
            status = TaskStatus.PENDING
        )
        return taskDao.insert(entity)
    }

    /**
     * 更新任务状态
     */
    suspend fun updateTaskStatus(
        taskId: Long,
        status: TaskStatus,
        progress: Float = 0f,
        downloaded: Int = 0,
        total: Int = 0,
        message: String = ""
    ) {
        val entity = taskDao.getTaskById(taskId) ?: return
        val updated = entity.copy(
            status = status,
            progress = progress,
            downloaded = downloaded,
            total = total,
            message = message
        )
        taskDao.update(updated)
    }

    /**
     * 删除任务
     */
    suspend fun deleteTask(taskId: Long) {
        val entity = taskDao.getTaskById(taskId) ?: return
        taskDao.delete(entity)
    }

    /**
     * 清除已完成/失败/取消的任务
     */
    suspend fun clearFinished() {
        taskDao.clearFinished()
    }

    /**
     * 解析 M3U8 URL
     */
    suspend fun parseM3U8(url: String): Result<M3U8Data> {
        return m3u8Parser.parse(url)
    }

    /**
     * 获取正在下载的任务数量
     */
    fun getDownloadingCount(): Flow<Int> {
        return taskDao.getDownloadingCount()
    }

    /**
     * 获取等待中的任务
     */
    suspend fun getPendingTasks(): List<DownloadTask> {
        return taskDao.getPendingTasks().map { it.toDomain() }
    }

    /**
     * TaskEntity 转 DownloadTask
     */
    private fun TaskEntity.toDomain(): DownloadTask {
        val headersMap = if (headers.isEmpty()) emptyMap() else {
            headers.split(",").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
        }
        return DownloadTask(
            id = id,
            url = url,
            outputName = outputName,
            outputDir = outputDir,
            headers = headersMap,
            status = status,
            progress = progress,
            downloaded = downloaded,
            total = total,
            message = message,
            createdAt = createdAt
        )
    }
}
