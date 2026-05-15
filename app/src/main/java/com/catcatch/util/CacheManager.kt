package com.catcatch.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 缓存管理器
 * 清理下载临时文件、转码临时文件、应用缓存
 */
object CacheManager {

    private const val TAG = "CacheManager"

    /**
     * 计算总缓存大小（字节）
     */
    fun getCacheSize(context: Context): Long {
        var size = 0L
        size += dirSize(context.cacheDir)
        size += dirSize(context.getExternalFilesDir("transcode"))
        return size
    }

    /**
     * 格式化缓存大小
     */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /**
     * 清除所有缓存
     * @return 清除的字节数
     */
    fun clearCache(context: Context): Long {
        var cleared = 0L
        cleared += deleteDirContents(context.cacheDir)
        cleared += deleteDir(context.getExternalFilesDir("transcode"))
        Log.i(TAG, "缓存清理完成: ${formatSize(cleared)}")
        return cleared
    }

    /**
     * 计算目录大小
     */
    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) dirSize(it) else it.length()
        }
        return size
    }

    /**
     * 删除目录内所有文件（保留目录本身）
     */
    private fun deleteDirContents(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var deleted = 0L
        dir.listFiles()?.forEach {
            deleted += if (it.isDirectory) {
                val s = dirSize(it)
                it.deleteRecursively()
                s
            } else {
                val s = it.length()
                it.delete()
                s
            }
        }
        return deleted
    }

    /**
     * 删除整个目录
     */
    private fun deleteDir(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        val size = dirSize(dir)
        dir.deleteRecursively()
        return size
    }
}
