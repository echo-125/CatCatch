package com.catcatch.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.catcatch.data.local.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 配置持久化仓库
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    val downloadDir: Flow<String> = dataStore.data.map {
        it[AppPreferences.DOWNLOAD_DIR] ?: AppPreferences.DEFAULT_DOWNLOAD_DIR
    }

    val downloadDirUri: Flow<String?> = dataStore.data.map {
        it[AppPreferences.DOWNLOAD_DIR_URI]
    }

    val useDirPicker: Flow<Boolean> = dataStore.data.map {
        it[AppPreferences.USE_DIR_PICKER] ?: AppPreferences.DEFAULT_USE_DIR_PICKER
    }

    val maxConcurrentTasks: Flow<Int> = dataStore.data.map {
        it[AppPreferences.MAX_CONCURRENT_TASKS] ?: AppPreferences.DEFAULT_MAX_CONCURRENT_TASKS
    }

    val maxConcurrentSegments: Flow<Int> = dataStore.data.map {
        it[AppPreferences.MAX_CONCURRENT_SEGMENTS] ?: AppPreferences.DEFAULT_MAX_CONCURRENT_SEGMENTS
    }

    val darkMode: Flow<Int> = dataStore.data.map {
        it[AppPreferences.DARK_MODE] ?: AppPreferences.DEFAULT_DARK_MODE
    }

    val transcodeMode: Flow<Int> = dataStore.data.map {
        it[AppPreferences.TRANSCODE_MODE] ?: AppPreferences.DEFAULT_TRANSCODE_MODE
    }

    val silentMode: Flow<Boolean> = dataStore.data.map {
        it[AppPreferences.SILENT_MODE] ?: AppPreferences.DEFAULT_SILENT_MODE
    }

    val browserTabs: Flow<String?> = dataStore.data.map {
        it[AppPreferences.BROWSER_TABS]
    }

    suspend fun setDownloadDir(value: String) {
        dataStore.edit { it[AppPreferences.DOWNLOAD_DIR] = value }
    }

    suspend fun setDownloadDirUri(value: String?) {
        dataStore.edit {
            if (value != null) {
                it[AppPreferences.DOWNLOAD_DIR_URI] = value
            } else {
                it.remove(AppPreferences.DOWNLOAD_DIR_URI)
            }
        }
    }

    suspend fun setUseDirPicker(value: Boolean) {
        dataStore.edit { it[AppPreferences.USE_DIR_PICKER] = value }
    }

    suspend fun setMaxConcurrentTasks(value: Int) {
        dataStore.edit { it[AppPreferences.MAX_CONCURRENT_TASKS] = value }
    }

    suspend fun setMaxConcurrentSegments(value: Int) {
        dataStore.edit { it[AppPreferences.MAX_CONCURRENT_SEGMENTS] = value }
    }

    suspend fun setDarkMode(value: Int) {
        dataStore.edit { it[AppPreferences.DARK_MODE] = value }
    }

    suspend fun setTranscodeMode(value: Int) {
        dataStore.edit { it[AppPreferences.TRANSCODE_MODE] = value }
    }

    suspend fun setSilentMode(value: Boolean) {
        dataStore.edit { it[AppPreferences.SILENT_MODE] = value }
    }

    suspend fun setBrowserTabs(value: String?) {
        dataStore.edit {
            if (value != null) {
                it[AppPreferences.BROWSER_TABS] = value
            } else {
                it.remove(AppPreferences.BROWSER_TABS)
            }
        }
    }
}
