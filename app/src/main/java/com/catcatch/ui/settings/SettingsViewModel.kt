package com.catcatch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catcatch.data.local.AppPreferences
import com.catcatch.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页状态
 */
data class SettingsState(
    val downloadDir: String = AppPreferences.DEFAULT_DOWNLOAD_DIR,
    val maxConcurrentTasks: Int = AppPreferences.DEFAULT_MAX_CONCURRENT_TASKS,
    val maxConcurrentSegments: Int = AppPreferences.DEFAULT_MAX_CONCURRENT_SEGMENTS,
    val darkMode: Int = AppPreferences.DEFAULT_DARK_MODE
)

/**
 * 设置页 ViewModel
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            launch {
                settingsRepository.downloadDir.collect { dir ->
                    _state.update { it.copy(downloadDir = dir) }
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

    fun updateDownloadDir(dir: String) {
        viewModelScope.launch { settingsRepository.setDownloadDir(dir) }
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
}
