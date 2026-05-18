package com.catcatch.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 浏览器快捷方式 DAO
 */
@Dao
interface ShortcutDao {

    /**
     * 获取所有快捷方式（按创建时间倒序）
     */
    @Query("SELECT * FROM shortcuts ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ShortcutEntity>>

    /**
     * 插入快捷方式（URL 重复时忽略）
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ShortcutEntity): Long

    /**
     * 根据 URL 删除快捷方式
     */
    @Query("DELETE FROM shortcuts WHERE url = :url")
    suspend fun deleteByUrl(url: String): Int

    /**
     * 检查快捷方式是否存在
     */
    @Query("SELECT EXISTS(SELECT 1 FROM shortcuts WHERE url = :url)")
    suspend fun existsByUrl(url: String): Boolean

    /**
     * 获取快捷方式数量
     */
    @Query("SELECT COUNT(*) FROM shortcuts")
    suspend fun count(): Int
}
