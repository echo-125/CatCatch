package com.catcatch.data.local

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore 配置 Keys
 */
object AppPreferences {
    val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
    val MAX_CONCURRENT_TASKS = intPreferencesKey("max_concurrent_tasks")
    val MAX_CONCURRENT_SEGMENTS = intPreferencesKey("max_concurrent_segments")
    val DARK_MODE = intPreferencesKey("dark_mode")  // 0=跟随系统, 1=浅色, 2=深色

    // 默认值
    const val DEFAULT_DOWNLOAD_DIR = "/storage/emulated/0/Download/CatCatch"
    const val DEFAULT_MAX_CONCURRENT_TASKS = 3
    const val DEFAULT_MAX_CONCURRENT_SEGMENTS = 8
    const val DEFAULT_DARK_MODE = 0
}
