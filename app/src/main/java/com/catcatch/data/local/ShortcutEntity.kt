package com.catcatch.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 浏览器快捷方式实体
 */
@Entity(
    tableName = "shortcuts",
    indices = [Index(value = ["url"], unique = true)]
)
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,           // 网站 URL
    val title: String,         // 显示名称
    val faviconUrl: String = "", // favicon URL（备用，主要使用 Google S2 服务）
    val createdAt: Long = System.currentTimeMillis()
)
