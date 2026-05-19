package com.catcatch.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 浏览器书签 DAO
 */
@Dao
interface BookmarkDao {

    /**
     * 获取所有书签（按创建时间倒序）
     */
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<BookmarkEntity>>

    /**
     * 插入书签（URL 重复时忽略）
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BookmarkEntity): Long

    /**
     * 根据 ID 删除书签
     */
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * 根据 URL 删除书签
     */
    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String): Int

    /**
     * 检查书签是否存在
     */
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    suspend fun existsByUrl(url: String): Boolean

    /**
     * 获取书签数量
     */
    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    /**
     * 更新书签标题
     */
    @Query("UPDATE bookmarks SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String): Int

    /**
     * 更新书签 URL
     */
    @Query("UPDATE bookmarks SET url = :url WHERE id = :id")
    suspend fun updateUrl(id: Long, url: String): Int

    /**
     * 更新书签 favicon
     */
    @Query("UPDATE bookmarks SET faviconUrl = :faviconUrl WHERE id = :id")
    suspend fun updateFavicon(id: Long, faviconUrl: String): Int
}
