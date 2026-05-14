package com.catcatch.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务数据访问对象
 */
@Dao
interface TaskDao {

    /**
     * 获取所有任务（按创建时间倒序）
     */
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    /**
     * 根据 ID 获取任务
     */
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TaskEntity?

    /**
     * 插入任务
     */
    @Insert
    suspend fun insert(task: TaskEntity): Long

    /**
     * 更新任务
     */
    @Update
    suspend fun update(task: TaskEntity)

    /**
     * 删除任务
     */
    @Delete
    suspend fun delete(task: TaskEntity)

    /**
     * 清除已完成/失败/取消的任务
     */
    @Query("DELETE FROM tasks WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()

    /**
     * 获取正在下载的任务数量
     */
    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'DOWNLOADING'")
    fun getDownloadingCount(): Flow<Int>

    /**
     * 获取等待中的任务
     */
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingTasks(): List<TaskEntity>
}
