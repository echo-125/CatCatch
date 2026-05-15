package com.catcatch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catcatch.data.local.AppPreferences
import com.catcatch.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val darkMode: Int = AppPreferences.DEFAULT_DARK_MODE
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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _event = MutableSharedFlow<SettingsEvent>()
    val event: SharedFlow<SettingsEvent> = _event.asSharedFlow()

    init {
        viewModelScope.launch {
            launch {
                settingsRepository.downloadDir.collect { dir ->
                    _state.update { it.copy(downloadDir = dir) }
                }
            }
            launch {
                settingsRepository.downloadDirUri.collect { uri ->
                    _state.update { it.copy(downloadDirUri = uri) }
                }
            }
            launch {
                settingsRepository.useDirPicker.collect { value ->
                    _state.update { it.copy(useDirPicker = value) }
                }
            }
            launch {
                settingsRepository.maxConcurrentTasks.collect { value ->
                    _state.update { it.copy(maxConcurrentTasks = value) }
                }
            }
            launch {
                settingsRepository.maxConcurrentSegments.collect { value ->
                    _state.update { it.copy(maxConcurrentSegments = value) }
                }
            }
            launch {
                settingsRepository.darkMode.collect { value ->
                    _state.update { it.copy(darkMode = value) }
                }
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
