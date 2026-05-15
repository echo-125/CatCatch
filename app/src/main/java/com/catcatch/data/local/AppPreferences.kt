package com.catcatch.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore 配置 Keys
 */
object AppPreferences {
    val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
    val DOWNLOAD_DIR_URI = stringPreferencesKey("download_dir_uri")
    val USE_DIR_PICKER = booleanPreferencesKey("use_dir_picker")
    val MAX_CONCURRENT_TASKS = intPreferencesKey("max_concurrent_tasks")
    val MAX_CONCURRENT_SEGMENTS = intPreferencesKey("max_concurrent_segments")
    val DARK_MODE = intPreferencesKey("dark_mode")  // 0=跟随系统, 1=浅色, 2=深色
    val TRANSCODE_MODE = intPreferencesKey("transcode_mode")  // 0=自动, 1=FFmpeg-kit, 2=系统原生

    // 默认值
    const val DEFAULT_DOWNLOAD_DIR = "/storage/emulated/0/Download/CatCatch"
    const val DEFAULT_USE_DIR_PICKER = true
    const val DEFAULT_MAX_CONCURRENT_TASKS = 3
    const val DEFAULT_MAX_CONCURRENT_SEGMENTS = 8
    const val DEFAULT_DARK_MODE = 0
    const val DEFAULT_TRANSCODE_MODE = 0
}
