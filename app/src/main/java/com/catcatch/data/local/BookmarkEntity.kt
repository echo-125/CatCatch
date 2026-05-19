package com.catcatch.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 浏览器书签实体
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String,
    val faviconUrl: String = "",
    val sniffMode: String = "",  // 嗅探模式名称，空字符串表示使用默认 AUTO
    val createdAt: Long = System.currentTimeMillis()
)
