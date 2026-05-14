package com.catcatch.ui.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catcatch.CatCatchApp
import com.catcatch.data.local.AppPreferences
import com.catcatch.data.repository.DownloadRepository
import com.catcatch.data.repository.SettingsRepository
import com.catcatch.domain.model.DownloadTask
import com.catcatch.domain.model.TaskStatus
import com.catcatch.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 输入状态（主页输入框）
 */
data class InputState(
    val url: String = "",
    val fileName: String = "",
    val headers: String = ""
)

/**
 * 任务列表状态（下载页）
 */
data class TaskListState(
    val tasks: List<DownloadTask> = emptyList()
)

/**
 * UI 事件状态（错误、成功、对话框等）
 */
data class UiEventState(
    val error: String? = null,
    val success: String? = null,
    val showBatchDialog: Boolean = false,
    val batchText: String = "",
    val isAdding: Boolean = false
)

/**
 * 主页 ViewModel
 * 使用 SavedStateHandle 持久化输入状态
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 当前下载目录，从 DataStore 同步
    private var currentDownloadDir = AppPreferences.DEFAULT_DOWNLOAD_DIR

    // 输入状态 — 仅主页输入框使用，支持进程恢复
    private val _inputState = MutableStateFlow(
        InputState(
            url = savedStateHandle["url"] ?: "",
            fileName = savedStateHandle["fileName"] ?: "",
            headers = savedStateHandle["headers"] ?: ""
        )
    )
    val inputState: StateFlow<InputState> = _inputState.asStateFlow()

    init {
        // 收集下载目录设置
        viewModelScope.launch {
            settingsRepository.downloadDir.collect { dir ->
                currentDownloadDir = dir
            }
        }
        // 收集 Deep Link 数据
        viewModelScope.launch {
            (context.applicationContext as CatCatchApp).deepLinkFlow.collect { data ->
                _inputState.update {
                    it.copy(
                        url = data.url,
                        fileName = data.title,
                        headers = formatHeadersFromMap(data.headers)
                    )
                }
            }
        }
    }

    // 任务列表状态 — 由 Room Flow 驱动，下载页使用
    val taskListState: StateFlow<TaskListState> = repository.getAllTasks()
        .map { TaskListState(tasks = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskListState()
        )

    // UI 事件状态 — 错误、对话框
    private val _uiEventState = MutableStateFlow(UiEventState())
    val uiEventState: StateFlow<UiEventState> = _uiEventState.asStateFlow()

    /**
     * 更新 URL 输入
     */
    fun onUrlChange(url: String) {
        _inputState.update { it.copy(url = url) }
        savedStateHandle["url"] = url
    }

    /**
     * 更新文件名输入
     */
    fun onFileNameChange(fileName: String) {
        _inputState.update { it.copy(fileName = fileName) }
        savedStateHandle["fileName"] = fileName
    }

    /**
     * 更新请求头输入
     */
    fun onHeadersChange(headers: String) {
        _inputState.update { it.copy(headers = headers) }
        savedStateHandle["headers"] = headers
    }

    /**
     * 添加任务
     */
    fun addTask() {
        val state = _inputState.value
        val url = state.url.trim()

        if (url.isEmpty()) {
            _uiEventState.update { it.copy(error = "请输入 M3U8 链接") }
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _uiEventState.update { it.copy(error = "请输入有效的 HTTP/HTTPS 链接") }
            return
        }

        viewModelScope.launch {
            _uiEventState.update { it.copy(isAdding = true, error = null) }

            try {
                val baseFileName = state.fileName.trim().ifEmpty {
                    generateFileName(url)
                }
                val fileName = getUniqueFileName(baseFileName, getDownloadDir())
                val headers = parseHeaders(state.headers)

                val taskId = repository.addTask(
                    url = url,
                    outputName = fileName,
                    outputDir = getDownloadDir(),
                    headers = headers
                )

                DownloadService.start(context, taskId)

                _inputState.update {
                    it.copy(url = "", fileName = "", headers = "")
                }
                savedStateHandle["url"] = ""
                savedStateHandle["fileName"] = ""
                savedStateHandle["headers"] = ""
                _uiEventState.update { it.copy(isAdding = false, success = "任务已添加，开始下载") }
            } catch (e: Exception) {
                _uiEventState.update {
                    it.copy(isAdding = false, error = "添加任务失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 删除任务
     */
    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
        }
    }

    /**
     * 清除已完成任务
     */
    fun clearFinished() {
        viewModelScope.launch {
            repository.clearFinished()
        }
    }

    /**
     * 取消下载任务
     */
    fun cancelTask(taskId: Long) {
        DownloadService.cancel(context, taskId)
    }

    /**
     * 重试下载任务
     */
    fun retryTask(taskId: Long) {
        viewModelScope.launch {
            repository.updateTaskStatus(taskId, TaskStatus.PENDING)
            DownloadService.start(context, taskId)
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiEventState.update { it.copy(error = null) }
    }

    /**
     * 打开下载目录
     */
    fun openFolder(task: DownloadTask) {
        val dir = java.io.File(task.outputDir)
        if (!dir.exists()) {
            _uiEventState.update { it.copy(error = "目录不存在") }
            return
        }

        try {
            // 直接使用 file:// URI 打开目录
            val uri = android.net.Uri.fromFile(dir)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 如果没有文件管理器能处理，尝试用 DocumentsContract
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = android.provider.DocumentsContract.buildRootUri(
                        "com.android.externalstorage.documents", "primary"
                    )
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                _uiEventState.update { it.copy(error = "目录: ${task.outputDir}") }
            }
        }
    }

    /**
     * 播放视频
     */
    fun playVideo(task: DownloadTask) {
        // 查找视频文件（优先 mp4，其次 ts）
        val outputDir = java.io.File(task.outputDir)
        val videoFile = findVideoFile(outputDir, task.outputName)

        if (videoFile == null) {
            _uiEventState.update { it.copy(error = "视频文件不存在") }
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            _uiEventState.update { it.copy(error = "无法播放视频，请安装视频播放器") }
        }
    }

    /**
     * 查找视频文件
     */
    private fun findVideoFile(dir: java.io.File, baseName: String): java.io.File? {
        if (!dir.exists()) return null

        // 优先查找 mp4
        val mp4File = java.io.File(dir, "$baseName.mp4")
        if (mp4File.exists() && mp4File.length() > 0) return mp4File

        // 其次查找 ts
        val tsFile = java.io.File(dir, "$baseName.ts")
        if (tsFile.exists() && tsFile.length() > 0) return tsFile

        // 查找带序号的文件 (1), (2) 等
        var counter = 1
        while (counter < 100) {
            val numberedMp4 = java.io.File(dir, "$baseName($counter).mp4")
            if (numberedMp4.exists() && numberedMp4.length() > 0) return numberedMp4

            val numberedTs = java.io.File(dir, "$baseName($counter).ts")
            if (numberedTs.exists() && numberedTs.length() > 0) return numberedTs

            counter++
        }

        return null
    }

    /**
     * 清除成功消息
     */
    fun clearSuccess() {
        _uiEventState.update { it.copy(success = null) }
    }

    /**
     * 显示批量添加对话框
     */
    fun showBatchDialog() {
        _uiEventState.update { it.copy(showBatchDialog = true, batchText = "") }
    }

    /**
     * 隐藏批量添加对话框
     */
    fun hideBatchDialog() {
        _uiEventState.update { it.copy(showBatchDialog = false, batchText = "") }
    }

    /**
     * 更新批量添加文本
     */
    fun onBatchTextChange(text: String) {
        _uiEventState.update { it.copy(batchText = text) }
    }

    /**
     * 批量添加任务
     */
    fun addBatchTasks() {
        val text = _uiEventState.value.batchText.trim()
        if (text.isEmpty()) {
            _uiEventState.update { it.copy(error = "请输入批量任务内容") }
            return
        }

        viewModelScope.launch {
            _uiEventState.update { it.copy(isAdding = true, error = null) }

            try {
                val lines = text.lines().filter { it.isNotBlank() }
                var addedCount = 0

                for (line in lines) {
                    val parts = line.split("|", limit = 3)
                    val url = parts[0].trim()

                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        continue
                    }

                    val baseFileName = parts.getOrNull(1)?.trim()?.ifEmpty { generateFileName(url) } ?: generateFileName(url)
                    val fileName = getUniqueFileName(baseFileName, getDownloadDir())

                    val taskId = repository.addTask(
                        url = url,
                        outputName = fileName,
                        outputDir = getDownloadDir()
                    )

                    DownloadService.start(context, taskId)
                    addedCount++
                }

                if (addedCount > 0) {
                    _uiEventState.update {
                        it.copy(
                            showBatchDialog = false,
                            batchText = "",
                            isAdding = false,
                            success = "已添加 $addedCount 个任务"
                        )
                    }
                } else {
                    _uiEventState.update {
                        it.copy(
                            isAdding = false,
                            error = "未找到有效链接"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiEventState.update {
                    it.copy(isAdding = false, error = "批量添加失败: ${e.message}")
                }
            }
        }
    }

    private fun generateFileName(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")
        val name = path.substringBeforeLast(".").substringAfterLast("/")
        return name.takeIf { it.isNotEmpty() } ?: "video_${System.currentTimeMillis()}"
    }

    private fun parseHeaders(headersStr: String): Map<String, String> {
        if (headersStr.isBlank()) return emptyMap()
        return headersStr.lines()
            .filter { it.contains(":") }
            .associate {
                val parts = it.split(":", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
    }

    private fun getDownloadDir(): String = currentDownloadDir

    private fun formatHeadersFromMap(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    /**
     * 获取去重后的文件名
     * 如果文件名已存在，自动添加 (1), (2) 等后缀
     */
    private suspend fun getUniqueFileName(baseName: String, outputDir: String): String {
        val existingNames = repository.getTaskOutputNames(outputDir)
        if (baseName !in existingNames) return baseName

        var counter = 1
        var uniqueName: String
        do {
            uniqueName = "$baseName($counter)"
            counter++
        } while (uniqueName in existingNames)

        return uniqueName
    }

    /**
     * 批量重试任务
     */
    fun batchRetry(taskIds: Set<Long>) {
        taskIds.forEach { retryTask(it) }
    }

    /**
     * 批量删除任务
     */
    fun batchDelete(taskIds: Set<Long>) {
        viewModelScope.launch {
            taskIds.forEach { repository.deleteTask(it) }
        }
    }
}
