package com.catcatch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.catcatch.data.local.AppPreferences
import com.catcatch.data.repository.SettingsRepository
import com.catcatch.util.CacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页状态
 */
data class SettingsState(
    val downloadDir: String = AppPreferences.DEFAULT_DOWNLOAD_DIR,
    val downloadDirUri: String? = null,
    val useDirPicker: Boolean = AppPreferences.DEFAULT_USE_DIR_PICKER,
    val maxConcurrentTasks: Int = AppPreferences.DEFAULT_MAX_CONCURRENT_TASKS,
    val maxConcurrentSegments: Int = AppPreferences.DEFAULT_MAX_CONCURRENT_SEGMENTS,
    val darkMode: Int = AppPreferences.DEFAULT_DARK_MODE,
    val transcodeMode: Int = AppPreferences.DEFAULT_TRANSCODE_MODE,
    val silentMode: Boolean = AppPreferences.DEFAULT_SILENT_MODE,
    val sslStrictMode: Boolean = AppPreferences.DEFAULT_BROWSER_SSL_STRICT,
    val cacheSize: Long = 0
)

/**
 * 设置页事件
 */
sealed interface SettingsEvent {
    data class ShowMessage(val message: String) : SettingsEvent
}

/**
 * 设置页 ViewModel
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _event = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<SettingsEvent> = _event.asSharedFlow()

    init {
        loadCacheSize()
        viewModelScope.launch {
            combine(
                settingsRepository.downloadDir,
                settingsRepository.downloadDirUri,
                settingsRepository.useDirPicker,
                settingsRepository.maxConcurrentTasks,
                settingsRepository.maxConcurrentSegments
            ) { dir, uri, usePicker, maxTasks, maxSegments ->
                arrayOf(dir, uri, usePicker, maxTasks, maxSegments)
            }.combine(
                combine(
                    settingsRepository.darkMode,
                    settingsRepository.transcodeMode,
                    settingsRepository.silentMode,
                    settingsRepository.browserSslStrict
                ) { dark, transcode, silent, sslStrict ->
                    arrayOf(dark, transcode, silent, sslStrict)
                }
            ) { first, second ->
                SettingsState(
                    downloadDir = first[0] as String,
                    downloadDirUri = first[1] as String?,
                    useDirPicker = first[2] as Boolean,
                    maxConcurrentTasks = first[3] as Int,
                    maxConcurrentSegments = first[4] as Int,
                    darkMode = second[0] as Int,
                    transcodeMode = second[1] as Int,
                    silentMode = second[2] as Boolean,
                    sslStrictMode = second[3] as Boolean
                )
            }.collect { newState ->
                _state.update { it.copy(
                    downloadDir = newState.downloadDir,
                    downloadDirUri = newState.downloadDirUri,
                    useDirPicker = newState.useDirPicker,
                    maxConcurrentTasks = newState.maxConcurrentTasks,
                    maxConcurrentSegments = newState.maxConcurrentSegments,
                    darkMode = newState.darkMode,
                    transcodeMode = newState.transcodeMode,
                    silentMode = newState.silentMode,
                    sslStrictMode = newState.sslStrictMode
                ) }
            }
        }
    }

    /**
     * 通过 SAF 选择器更新下载目录
     */
    fun updateDownloadDirFromSaf(displayPath: String, uri: String) {
        viewModelScope.launch {
            settingsRepository.setDownloadDir(displayPath)
            settingsRepository.setDownloadDirUri(uri)
            _event.emit(SettingsEvent.ShowMessage("下载目录已更新"))
        }
    }

    /**
     * 手动输入路径更新下载目录
     */
    fun updateDownloadDir(dir: String) {
        viewModelScope.launch {
            settingsRepository.setDownloadDir(dir.trim())
            settingsRepository.setDownloadDirUri(null) // 手动输入时清除 URI
            _event.emit(SettingsEvent.ShowMessage("下载目录已更新"))
        }
    }

    fun updateUseDirPicker(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUseDirPicker(value) }
    }

    fun updateMaxConcurrentTasks(value: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentTasks(value) }
    }

    fun updateMaxConcurrentSegments(value: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentSegments(value) }
    }

    fun updateDarkMode(value: Int) {
        viewModelScope.launch { settingsRepository.setDarkMode(value) }
    }

    fun updateTranscodeMode(value: Int) {
        viewModelScope.launch { settingsRepository.setTranscodeMode(value) }
    }

    fun updateSilentMode(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSilentMode(value) }
    }

    fun updateSslStrictMode(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBrowserSslStrict(value) }
    }

    fun loadCacheSize() {
        viewModelScope.launch {
            val size = CacheManager.getCacheSize(context)
            _state.update { it.copy(cacheSize = size) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            CacheManager.clearCache(context)
            _state.update { it.copy(cacheSize = 0) }
            _event.emit(SettingsEvent.ShowMessage("缓存已清除"))
        }
    }

    /**
     * 验证目录路径格式是否有效
     */
    fun isValidDirPath(path: String): Boolean {
        val validPrefixes = listOf(
            "/storage/emulated/0/",
            "/storage/",
            "/sdcard/"
        )
        return validPrefixes.any { path.startsWith(it) }
    }
}
