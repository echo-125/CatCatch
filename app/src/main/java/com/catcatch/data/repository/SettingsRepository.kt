package com.catcatch.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.catcatch.data.local.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 配置持久化仓库
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val downloadDir: Flow<String> = dataStore.data.map {
        it[AppPreferences.DOWNLOAD_DIR] ?: AppPreferences.DEFAULT_DOWNLOAD_DIR
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

    suspend fun setDownloadDir(value: String) {
        dataStore.edit { it[AppPreferences.DOWNLOAD_DIR] = value }
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
}
