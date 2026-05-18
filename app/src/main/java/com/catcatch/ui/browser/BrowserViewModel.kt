package com.catcatch.ui.browser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catcatch.data.local.AppPreferences
import com.catcatch.data.local.ShortcutDao
import com.catcatch.data.local.ShortcutEntity
import com.catcatch.data.remote.M3U8Parser
import com.catcatch.data.repository.DownloadRepository
import com.catcatch.data.repository.SettingsRepository
import com.catcatch.service.DownloadService
import com.catcatch.ui.browser.config.SiteConfigs
import com.catcatch.ui.browser.model.SniffedLink
import com.catcatch.ui.browser.model.SniffSource
import com.catcatch.ui.browser.model.SiteConfig
import com.catcatch.ui.browser.model.Tab
import com.catcatch.ui.browser.model.Variant
import com.catcatch.ui.browser.model.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * 浏览器模式
 */
enum class BrowserMode {
    NEW_TAB,      // 新标签页
    BROWSING,     // 网页浏览
    TAB_MANAGER   // 标签管理器
}

/**
 * 嗅探模式
 */
enum class SniffMode(val label: String) {
    AUTO("自动"),
    NETWORK("网络拦截"),
    DOM("DOM 监听"),
    DEEP_SCAN("深度扫描")
}

/**
 * 浏览器状态
 */
data class BrowserState(
    val tabs: List<Tab> = listOf(Tab()),
    val activeTabId: String = tabs.firstOrNull()?.id ?: "",
    val mode: BrowserMode = BrowserMode.NEW_TAB,
    val isTabManagerOpen: Boolean = false,
    val isSnifferPanelOpen: Boolean = false,
    val sniffMode: SniffMode = SniffMode.AUTO,
    val sniffedLinks: List<SniffedLink> = emptyList(),
    val siteConfig: SiteConfig? = null,
    val logs: List<String> = emptyList(),
    val success: String? = null,
    val error: String? = null
) {
    /**
     * 获取当前活跃标签
     */
    val activeTab: Tab?
        get() = tabs.find { it.id == activeTabId }

    /**
     * 标签数量
     */
    val tabCount: Int
        get() = tabs.size

    /**
     * 当前 URL
     */
    val currentUrl: String
        get() = activeTab?.url ?: ""

    /**
     * 页面标题
     */
    val pageTitle: String
        get() = activeTab?.title ?: ""

    /**
     * 是否正在加载
     */
    val isLoading: Boolean
        get() = activeTab?.isLoading ?: false

    /**
     * 是否可以后退
     */
    val canGoBack: Boolean
        get() = activeTab?.canGoBack ?: false

    /**
     * 是否可以前进
     */
    val canGoForward: Boolean
        get() = activeTab?.canGoForward ?: false

    /**
     * 当前标签是否已收藏
     */
    val isCurrentTabFavorite: Boolean
        get() = activeTab?.isFavorite ?: false
}

/**
 * 浏览器 ViewModel
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val shortcutDao: ShortcutDao,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val _shortcuts = MutableStateFlow<List<ShortcutEntity>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutEntity>> = _shortcuts.asStateFlow()

    private val m3u8Parser = M3U8Parser(okHttpClient)
    private var currentDownloadDir = AppPreferences.DEFAULT_DOWNLOAD_DIR
    private val capturedUrls = mutableSetOf<String>()

    init {
        // 加载快捷方式
        viewModelScope.launch {
            shortcutDao.getAll().collect { list ->
                _shortcuts.value = list
            }
        }
        // 加载下载目录配置
        viewModelScope.launch {
            settingsRepository.downloadDir.collect { dir ->
                currentDownloadDir = dir
            }
        }
    }

    // ==================== 标签页管理 ====================

    /**
     * 新建标签页
     */
    fun newTab() {
        val newTab = Tab()
        _state.update {
            it.copy(
                tabs = it.tabs + newTab,
                activeTabId = newTab.id,
                mode = BrowserMode.NEW_TAB
            )
        }
    }

    /**
     * 关闭标签页
     */
    fun closeTab(tabId: String) {
        _state.update { state ->
            val newTabs = state.tabs.filter { it.id != tabId }

            // 如果没有标签了，创建一个新的
            if (newTabs.isEmpty()) {
                val newTab = Tab()
                state.copy(
                    tabs = listOf(newTab),
                    activeTabId = newTab.id,
                    mode = BrowserMode.NEW_TAB,
                    isTabManagerOpen = false
                )
            } else {
                // 如果关闭的是当前标签，切换到相邻标签
                val newActiveTabId = if (tabId == state.activeTabId) {
                    val index = state.tabs.indexOfFirst { it.id == tabId }
                    val newIndex = if (index > 0) index - 1 else 0
                    newTabs[newIndex].id
                } else {
                    state.activeTabId
                }

                state.copy(
                    tabs = newTabs,
                    activeTabId = newActiveTabId,
                    mode = if (newTabs.find { it.id == newActiveTabId }?.isNewTab == true) {
                        BrowserMode.NEW_TAB
                    } else {
                        BrowserMode.BROWSING
                    },
                    isTabManagerOpen = false
                )
            }
        }
    }

    /**
     * 切换标签页
     */
    fun switchTab(tabId: String) {
        _state.update { state ->
            val tab = state.tabs.find { it.id == tabId } ?: return@update state
            state.copy(
                activeTabId = tabId,
                mode = if (tab.isNewTab) BrowserMode.NEW_TAB else BrowserMode.BROWSING,
                isTabManagerOpen = false,
                sniffedLinks = emptyList()
            )
        }
        // 清空已捕获的 URL
        capturedUrls.clear()
    }

    /**
     * 切换标签管理器
     */
    fun toggleTabManager() {
        _state.update {
            it.copy(isTabManagerOpen = !it.isTabManagerOpen)
        }
    }

    /**
     * 关闭标签管理器
     */
    fun closeTabManager() {
        _state.update {
            it.copy(isTabManagerOpen = false)
        }
    }

    // ==================== URL 和导航 ====================

    fun onUrlChange(url: String) {
        _state.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == state.activeTabId) {
                    tab.copy(url = url)
                } else {
                    tab
                }
            }
            state.copy(tabs = updatedTabs)
        }
    }

    fun loadUrl(url: String) {
        if (url.isBlank()) return

        val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        // 清空之前的状态
        capturedUrls.clear()

        _state.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == state.activeTabId) {
                    tab.copy(
                        url = finalUrl,
                        isLoading = true,
                        title = ""
                    )
                } else {
                    tab
                }
            }
            state.copy(
                tabs = updatedTabs,
                mode = BrowserMode.BROWSING,
                sniffedLinks = emptyList(),
                logs = emptyList(),
                siteConfig = SiteConfigs.getConfig(finalUrl)
            )
        }
    }

    /**
     * 返回主页（新标签页）
     */
    fun goHome() {
        _state.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == state.activeTabId) {
                    tab.copy(url = "", title = "新标签页")
                } else {
                    tab
                }
            }
            state.copy(
                tabs = updatedTabs,
                mode = BrowserMode.NEW_TAB,
                sniffedLinks = emptyList()
            )
        }
    }

    /**
     * 切换收藏状态（同时管理快捷方式）
     */
    fun toggleFavorite() {
        val tab = _state.value.activeTab ?: return
        if (tab.url.isEmpty()) return

        viewModelScope.launch {
            val isCurrentlyFavorite = shortcutDao.existsByUrl(tab.url)
            if (isCurrentlyFavorite) {
                shortcutDao.deleteByUrl(tab.url)
            } else {
                if (shortcutDao.count() < MAX_SHORTCUTS) {
                    val title = tab.title.ifEmpty {
                        Uri.parse(tab.url).host ?: tab.url
                    }
                    shortcutDao.insert(
                        ShortcutEntity(
                            url = tab.url,
                            title = title.take(MAX_TITLE_LENGTH)
                        )
                    )
                }
            }
            updateActiveTab { it.copy(isFavorite = !isCurrentlyFavorite) }
        }
    }

    /**
     * 移除快捷方式
     */
    fun removeShortcut(url: String) {
        viewModelScope.launch {
            shortcutDao.deleteByUrl(url)
            // 同步更新当前标签的收藏状态
            val currentUrl = _state.value.currentUrl
            if (currentUrl == url) {
                updateActiveTab { it.copy(isFavorite = false) }
            }
        }
    }

    fun onPageStarted(url: String) {
        updateActiveTab { it.copy(isLoading = true) }
    }

    fun onPageFinished(url: String) {
        updateActiveTab { it.copy(isLoading = false, url = url) }
        // 同步数据库中的收藏状态
        viewModelScope.launch {
            val isFav = shortcutDao.existsByUrl(url)
            updateActiveTab { it.copy(isFavorite = isFav) }
        }
    }

    fun updateNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        updateActiveTab { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun onPageTitleChanged(title: String) {
        if (title.isNotBlank()) {
            updateActiveTab { it.copy(title = title) }
        }
    }

    private fun updateActiveTab(transform: (Tab) -> Tab) {
        _state.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == state.activeTabId) {
                    transform(tab)
                } else {
                    tab
                }
            }
            state.copy(tabs = updatedTabs)
        }
    }

    // ==================== 嗅探处理 ====================

    fun onM3u8Sniffed(url: String, headers: Map<String, String>, source: SniffSource = SniffSource.DOM) {
        if (url.isBlank() || url in capturedUrls) return
        capturedUrls.add(url)

        viewModelScope.launch {
            try {
                parseM3u8Link(url, null, headers, source)
            } catch (e: Exception) {
                addLog("解析失败: ${e.message}")
            }
        }
    }

    fun onNetworkM3u8Sniffed(url: String) {
        if (url.isBlank() || url in capturedUrls) return
        capturedUrls.add(url)

        val headers = extractHeadersFromUrl(url)

        viewModelScope.launch {
            try {
                parseM3u8Link(url, null, headers, SniffSource.NETWORK)
            } catch (e: Exception) {
                addLog("解析失败: ${e.message}")
            }
        }
    }

    private suspend fun parseM3u8Link(
        url: String,
        content: String?,
        headers: Map<String, String>,
        source: SniffSource
    ) {
        addLog("开始解析: ${url.substringAfterLast('/')}")

        try {
            val result = m3u8Parser.parse(url, headers)
            val m3u8Data = result.getOrThrow()

            val link = SniffedLink(
                url = url,
                fileName = generateFileName(url),
                headers = headers,
                duration = m3u8Data.segments.sumOf { it.duration },
                source = source
            )

            addSniffedLink(link)
            addLog("解析成功: ${formatDuration(link.duration)}")

        } catch (e: Exception) {
            try {
                val fetchedContent = content ?: fetchContent(url, headers)
                if (fetchedContent != null && fetchedContent.contains("#EXT-X-STREAM-INF")) {
                    val variants = parseVariants(fetchedContent, url)
                    val link = SniffedLink(
                        url = url,
                        fileName = generateFileName(url),
                        headers = headers,
                        variants = variants,
                        isPlaylist = true,
                        source = source
                    )
                    addSniffedLink(link)
                    addLog("发现播放列表: ${variants.size} 个分辨率")
                } else if (fetchedContent != null && fetchedContent.contains("#EXTM3U")) {
                    val duration = parseDurationFromContent(fetchedContent)
                    val link = SniffedLink(
                        url = url,
                        fileName = generateFileName(url),
                        headers = headers,
                        duration = duration,
                        source = source
                    )
                    addSniffedLink(link)
                    addLog("解析成功: ${formatDuration(duration)}")
                } else {
                    addLog("非 M3U8 内容，跳过")
                }
            } catch (e2: Exception) {
                addLog("解析失败: ${e2.message}")
            }
        }
    }

    private fun addSniffedLink(link: SniffedLink) {
        _state.update {
            it.copy(sniffedLinks = it.sniffedLinks + link)
        }
    }

    // ==================== 深度扫描 ====================

    fun triggerDeepScan() {
        addLog("触发深度扫描...")
    }

    // ==================== 添加任务 ====================

    fun addTask(link: SniffedLink) {
        viewModelScope.launch {
            try {
                val fileName = getUniqueFileName(link.fileName.ifEmpty { generateFileName(link.url) }, currentDownloadDir)

                val taskId = repository.addTask(
                    url = link.selectedUrl,
                    outputName = fileName,
                    outputDir = currentDownloadDir,
                    headers = link.headers
                )

                DownloadService.start(context, taskId)

                _state.update {
                    it.copy(success = "任务已添加: $fileName")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = "添加任务失败: ${e.message}")
                }
            }
        }
    }

    fun addTask(url: String) {
        val link = _state.value.sniffedLinks.find { it.url == url }
        if (link != null) {
            addTask(link)
        } else {
            viewModelScope.launch {
                try {
                    val fileName = getUniqueFileName(generateFileName(url), currentDownloadDir)
                    val headers = extractHeadersFromUrl(url)

                    val taskId = repository.addTask(
                        url = url,
                        outputName = fileName,
                        outputDir = currentDownloadDir,
                        headers = headers
                    )

                    DownloadService.start(context, taskId)

                    _state.update {
                        it.copy(success = "任务已添加: $fileName")
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(error = "添加任务失败: ${e.message}")
                    }
                }
            }
        }
    }

    // ==================== UI 状态 ====================

    /**
     * 切换嗅探面板展开/收起
     */
    fun toggleSnifferPanel() {
        _state.update { it.copy(isSnifferPanelOpen = !it.isSnifferPanelOpen) }
    }

    fun closeSnifferPanel() {
        _state.update { it.copy(isSnifferPanelOpen = false) }
    }

    /**
     * 设置嗅探模式
     */
    fun setSniffMode(mode: SniffMode) {
        _state.update { it.copy(sniffMode = mode) }
    }

    /**
     * 选择指定链接的变体（分辨率）
     */
    fun selectVariant(linkUrl: String, variantIndex: Int) {
        _state.update { state ->
            val updatedLinks = state.sniffedLinks.map { link ->
                if (link.url == linkUrl) link.copy(selectedVariantIndex = variantIndex) else link
            }
            state.copy(sniffedLinks = updatedLinks)
        }
    }

    /**
     * 批量添加所有嗅探到的链接
     */
    fun addAllTasks() {
        val links = _state.value.sniffedLinks
        if (links.isEmpty()) return

        viewModelScope.launch {
            var addedCount = 0
            for (link in links) {
                try {
                    val fileName = getUniqueFileName(
                        link.fileName.ifEmpty { generateFileName(link.url) },
                        currentDownloadDir
                    )
                    val taskId = repository.addTask(
                        url = link.selectedUrl,
                        outputName = fileName,
                        outputDir = currentDownloadDir,
                        headers = link.headers
                    )
                    DownloadService.start(context, taskId)
                    addedCount++
                } catch (_: Exception) { }
            }
            _state.update {
                it.copy(
                    success = "已添加 $addedCount 个任务",
                    isSnifferPanelOpen = false
                )
            }
        }
    }

    fun clearSniffedLinks() {
        capturedUrls.clear()
        _state.update {
            it.copy(sniffedLinks = emptyList())
        }
    }

    fun addLog(message: String) {
        _state.update {
            val newLogs = (it.logs + message).takeLast(100)
            it.copy(logs = newLogs)
        }
    }

    fun clearSuccess() {
        _state.update { it.copy(success = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ==================== 辅助函数 ====================

    private fun generateFileName(url: String): String {
        val pageTitle = _state.value.pageTitle
        if (pageTitle.isNotBlank()) {
            return pageTitle.replace(Regex("[<>:\"/\\\\|?*]"), "_")
                .replace(Regex("\\s+"), "_")
                .take(50)
        }

        val path = url.substringBefore("?").substringAfterLast("/")
        var name = path.substringBeforeLast(".").substringAfterLast("/")

        if (name.isEmpty() || name in listOf("index", "playlist", "master", "video", "stream")) {
            val pathSegments = url.substringBefore("?").substringAfter("://").split("/")
            for (segment in pathSegments.reversed()) {
                val cleanSegment = segment.substringBeforeLast(".").substringBefore("?")
                if (cleanSegment.isNotEmpty() && cleanSegment !in listOf("index", "playlist", "master", "video", "stream", "hls", "m3u8")) {
                    name = cleanSegment
                    break
                }
            }
        }

        return name.takeIf { it.isNotEmpty() && it.length > 2 } ?: "video_${System.currentTimeMillis() / 1000}"
    }

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

    private fun extractHeadersFromUrl(url: String): Map<String, String> {
        return try {
            val uri = java.net.URI(url)
            val origin = "${uri.scheme}://${uri.host}"
            mapOf("origin" to origin, "referer" to origin)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun fetchContent(url: String, headers: Map<String, String>): String? {
        return try {
            val requestBuilder = okhttp3.Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.body?.string()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVariants(content: String, baseUrl: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        val lines = content.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val resolution = Regex("RESOLUTION=([\\d]+x[\\d]+)").find(line)?.groupValues?.get(1)
            val codecs = Regex("CODECS=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            val frameRate = Regex("FRAME-RATE=([\\d.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()

            val nextLine = lines.getOrNull(i + 1)?.trim()
            if (nextLine != null && !nextLine.startsWith("#")) {
                val variantUrl = if (nextLine.startsWith("http")) {
                    nextLine
                } else {
                    baseUrl.substringBeforeLast("/") + "/" + nextLine
                }

                variants.add(
                    Variant(
                        url = variantUrl,
                        bandwidth = bandwidth,
                        resolution = resolution,
                        codecs = codecs,
                        frameRate = frameRate
                    )
                )
            }
        }

        return variants.sortedByDescending { it.bandwidth }
    }

    private fun parseDurationFromContent(content: String): Double {
        var totalDuration = 0.0
        val regex = Regex("#EXTINF:([\\d.]+)")
        for (line in content.lines()) {
            val matchResult = regex.find(line.trim())
            if (matchResult != null) {
                totalDuration += matchResult.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }
        return totalDuration
    }

    companion object {
        /** 最大快捷方式数量 */
        private const val MAX_SHORTCUTS = 12
        /** 快捷方式标题最大长度 */
        private const val MAX_TITLE_LENGTH = 20
    }
}
