package com.catcatch.ui.browser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catcatch.data.local.AppPreferences
import com.catcatch.data.local.BookmarkDao
import com.catcatch.data.local.BookmarkEntity
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * 浏览器模式
 */
enum class BrowserMode {
    NEW_TAB,      // 新标签页
    BROWSING      // 网页浏览
}

/**
 * 嗅探模式
 */
enum class SniffMode(val label: String) {
    AUTO("自动"),
    NETWORK("网络拦截"),
    DOM("DOM 监听"),
    DEEP_SCAN("深度扫描"),
    DISGUISE("伪装")
}

/**
 * 嗅探状态
 */
enum class SniffState {
    IDLE,       // 空闲，未嗅探
    SCANNING,   // 正在嗅探中
    FOUND,      // 已发现链接
    EMPTY,      // 嗅探完成但未发现链接
    ERROR       // 嗅探出错
}

/**
 * 浏览器状态
 */
data class BrowserState(
    val tabs: List<Tab> = listOf(Tab()),
    val activeTabId: String = tabs.firstOrNull()?.id ?: "",
    val mode: BrowserMode = BrowserMode.NEW_TAB,
    val isTabManagerOpen: Boolean = false,
    val isBookmarkManagerOpen: Boolean = false,
    val isSnifferPanelOpen: Boolean = false,
    val sniffMode: SniffMode = SniffMode.AUTO,
    val siteConfig: SiteConfig? = null,
    val sslStrictMode: Boolean = true,
    val logs: List<String> = emptyList(),
    val success: String? = null,
    val error: String? = null
) {
    val activeTab: Tab?
        get() = tabs.find { it.id == activeTabId }

    val tabCount: Int
        get() = tabs.size

    val currentUrl: String
        get() = activeTab?.url ?: ""

    val pageTitle: String
        get() = activeTab?.title ?: ""

    val isLoading: Boolean
        get() = activeTab?.isLoading ?: false

    val canGoBack: Boolean
        get() = activeTab?.canGoBack ?: false

    val canGoForward: Boolean
        get() = activeTab?.canGoForward ?: false

    val isCurrentTabBookmarked: Boolean
        get() = activeTab?.isBookmarked ?: false

    val activeTabSniffedLinks: List<SniffedLink>
        get() = activeTab?.sniffedLinks ?: emptyList()

    // 新增: 嗅探状态（从 activeTab 派生）
    val sniffState: SniffState
        get() = activeTab?.sniffState ?: SniffState.IDLE

    val sniffProgress: String
        get() = activeTab?.sniffProgress ?: ""

    // 新增: 是否正在嗅探
    val isSniffing: Boolean
        get() = sniffState == SniffState.SCANNING
}

/**
 * 浏览器 ViewModel
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkDao: BookmarkDao,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks.asStateFlow()

    private val m3u8Parser = M3U8Parser(okHttpClient)
    private var currentDownloadDir = AppPreferences.DEFAULT_DOWNLOAD_DIR
    private val capturedUrls = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun getCapturedUrls(tabId: String?): MutableSet<String> {
        val id = tabId ?: _state.value.activeTabId ?: return mutableSetOf()
        return capturedUrls.getOrPut(id) { java.util.Collections.synchronizedSet(mutableSetOf()) }
    }
    private var jsExecutor: ((String) -> Unit)? = null
    private var webviewAction: ((String) -> Unit)? = null

    fun registerJsExecutor(executor: ((String) -> Unit)?) {
        jsExecutor = executor
    }

    fun registerWebviewAction(action: ((String) -> Unit)?) {
        webviewAction = action
    }

    fun goBack() {
        webviewAction?.invoke("back")
    }

    fun goForward() {
        webviewAction?.invoke("forward")
    }

    init {
        // 加载书签
        viewModelScope.launch {
            bookmarkDao.getAll().collect { list ->
                _bookmarks.value = list
            }
        }
        // 加载下载目录配置
        viewModelScope.launch {
            settingsRepository.downloadDir.collect { dir ->
                currentDownloadDir = dir
            }
        }
        // 加载 SSL 严格模式配置
        viewModelScope.launch {
            settingsRepository.browserSslStrict.collect { strict ->
                _state.update { it.copy(sslStrictMode = strict) }
            }
        }
        // 恢复标签页
        viewModelScope.launch {
            val json = settingsRepository.browserTabs.firstOrNull()
            if (json != null) {
                val restored = deserializeTabs(json)
                if (restored != null) {
                    _state.update {
                        it.copy(
                            tabs = restored.first,
                            activeTabId = restored.second,
                            mode = if (restored.first.find { t -> t.id == restored.second }?.isNewTab != false) {
                                BrowserMode.NEW_TAB
                            } else {
                                BrowserMode.BROWSING
                            }
                        )
                    }
                }
            }
        }
        // 标签页变化时自动持久化
        var lastSavedTabsJson: String? = null
        viewModelScope.launch {
            _state.collect { state ->
                val json = serializeTabs(state.tabs, state.activeTabId)
                if (json != lastSavedTabsJson) {
                    lastSavedTabsJson = json
                    settingsRepository.setBrowserTabs(json)
                }
            }
        }
    }

    // ==================== 标签页管理 ====================

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

    fun closeTab(tabId: String) {
        _state.update { state ->
            val newTabs = state.tabs.filter { it.id != tabId }
            val isActiveTab = tabId == state.activeTabId

            if (newTabs.isEmpty()) {
                val newTab = Tab()
                state.copy(
                    tabs = listOf(newTab),
                    activeTabId = newTab.id,
                    mode = BrowserMode.NEW_TAB,
                    isTabManagerOpen = false
                )
            } else {
                val newActiveTabId = if (isActiveTab) {
                    val index = state.tabs.indexOfFirst { it.id == tabId }
                    val newIndex = if (index > 0) index - 1 else 0
                    newTabs[newIndex].id
                } else {
                    state.activeTabId
                }

                state.copy(
                    tabs = newTabs,
                    activeTabId = newActiveTabId,
                    mode = if (isActiveTab) {
                        if (newTabs.find { it.id == newActiveTabId }?.isNewTab == true) {
                            BrowserMode.NEW_TAB
                        } else {
                            BrowserMode.BROWSING
                        }
                    } else {
                        state.mode
                    },
                    isTabManagerOpen = if (isActiveTab) false else state.isTabManagerOpen
                )
            }
        }
        capturedUrls.remove(tabId)
    }

    fun closeAllTabs() {
        val newTab = Tab()
        _state.update {
            it.copy(
                tabs = listOf(newTab),
                activeTabId = newTab.id,
                mode = BrowserMode.NEW_TAB,
                isTabManagerOpen = false
            )
        }
        capturedUrls.clear()
    }

    fun switchTab(tabId: String) {
        _state.update { state ->
            val tab = state.tabs.find { it.id == tabId } ?: return@update state
            state.copy(
                activeTabId = tabId,
                mode = if (tab.isNewTab) BrowserMode.NEW_TAB else BrowserMode.BROWSING,
                isTabManagerOpen = false,
                isSnifferPanelOpen = false
            )
        }
    }

    fun toggleTabManager() {
        _state.update {
            it.copy(isTabManagerOpen = !it.isTabManagerOpen)
        }
    }

    fun closeTabManager() {
        _state.update {
            it.copy(isTabManagerOpen = false)
        }
    }

    // ==================== 书签管理 ====================

    fun toggleBookmarkManager() {
        _state.update {
            it.copy(isBookmarkManagerOpen = !it.isBookmarkManagerOpen)
        }
    }

    fun closeBookmarkManager() {
        _state.update {
            it.copy(isBookmarkManagerOpen = false)
        }
    }

    fun toggleBookmark() {
        val tab = _state.value.activeTab ?: return
        if (tab.url.isEmpty()) return

        viewModelScope.launch {
            val isCurrentlyBookmarked = bookmarkDao.existsByUrl(tab.url)
            if (isCurrentlyBookmarked) {
                bookmarkDao.deleteByUrl(tab.url)
            } else {
                val title = tab.title.ifEmpty {
                    Uri.parse(tab.url).host ?: tab.url
                }
                val faviconUrl = generateFaviconUrl(tab.url)
                bookmarkDao.insert(
                    BookmarkEntity(
                        url = tab.url,
                        title = title.take(MAX_TITLE_LENGTH),
                        faviconUrl = faviconUrl
                    )
                )
            }
            updateActiveTab { it.copy(isBookmarked = !isCurrentlyBookmarked) }
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            bookmarkDao.deleteByUrl(url)
            val currentUrl = _state.value.currentUrl
            if (currentUrl == url) {
                updateActiveTab { it.copy(isBookmarked = false) }
            }
        }
    }

    fun removeBookmarkById(id: Long) {
        viewModelScope.launch {
            val bookmark = _bookmarks.value.find { it.id == id }
            bookmark?.let {
                bookmarkDao.deleteById(id)
                val currentUrl = _state.value.currentUrl
                if (currentUrl == it.url) {
                    updateActiveTab { tab -> tab.copy(isBookmarked = false) }
                }
            }
        }
    }

    fun updateBookmark(id: Long, newTitle: String, newUrl: String) {
        viewModelScope.launch {
            val oldBookmark = _bookmarks.value.find { it.id == id }
            if (oldBookmark != null) {
                if (oldBookmark.title != newTitle) {
                    bookmarkDao.updateTitle(id, newTitle)
                }
                if (oldBookmark.url != newUrl) {
                    bookmarkDao.updateUrl(id, newUrl)
                    val faviconUrl = generateFaviconUrl(newUrl)
                    bookmarkDao.updateFavicon(id, faviconUrl)
                }
            }
        }
    }

    fun addBookmark(title: String, url: String) {
        viewModelScope.launch {
            val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val faviconUrl = generateFaviconUrl(finalUrl)
            bookmarkDao.insert(
                BookmarkEntity(
                    url = finalUrl,
                    title = title.take(MAX_TITLE_LENGTH),
                    faviconUrl = faviconUrl
                )
            )
        }
    }

    fun deleteSelectedBookmarks(selectedIds: Set<Long>) {
        viewModelScope.launch {
            selectedIds.forEach { id ->
                bookmarkDao.deleteById(id)
                val bookmark = _bookmarks.value.find { it.id == id }
                bookmark?.let {
                    val currentUrl = _state.value.currentUrl
                    if (currentUrl == it.url) {
                        updateActiveTab { tab -> tab.copy(isBookmarked = false) }
                    }
                }
            }
        }
    }

    fun loadBookmark(url: String) {
        loadUrl(url)
        closeBookmarkManager()
    }

    // ==================== URL 和导航 ====================

    fun loadUrl(url: String) {
        if (url.isBlank()) return

        val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        getCapturedUrls(_state.value.activeTabId).clear()

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
                logs = emptyList(),
                siteConfig = SiteConfigs.getConfig(finalUrl)
            )
        }

        // 查找域名对应的嗅探模式绑定（仅在有绑定时切换，无绑定时保留当前模式）
        viewModelScope.launch {
            val matchingBookmark = findBookmarkForDomain(finalUrl)
            if (matchingBookmark != null && matchingBookmark.sniffMode.isNotEmpty()) {
                val mode = try {
                    SniffMode.valueOf(matchingBookmark.sniffMode)
                } catch (_: Exception) {
                    null
                }
                mode?.let {
                    _state.update { state -> state.copy(sniffMode = it) }
                    addLog("应用站点嗅探模式: ${it.label}")
                }
            }
            // 无绑定时保留用户当前选择的模式，不重置
        }
    }

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
                mode = BrowserMode.NEW_TAB
            )
        }
    }

    fun onPageStarted(url: String, tabId: String? = null) {
        updateActiveTab(tabId) { it.copy(isLoading = true) }
    }

    fun onPageFinished(url: String, tabId: String? = null) {
        updateActiveTab(tabId) { it.copy(isLoading = false, url = url) }
        viewModelScope.launch {
            val isBookmarked = bookmarkDao.existsByUrl(url)
            updateActiveTab(tabId) { it.copy(isBookmarked = isBookmarked) }
        }
    }

    fun updateNavigationState(canGoBack: Boolean, canGoForward: Boolean, tabId: String? = null) {
        updateActiveTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun onPageTitleChanged(title: String, tabId: String? = null) {
        if (title.isNotBlank()) {
            updateActiveTab(tabId) { it.copy(title = title) }
        }
    }

    private fun updateActiveTab(tabId: String? = null, transform: (Tab) -> Tab) {
        _state.update { state ->
            val targetId = tabId ?: state.activeTabId
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == targetId) {
                    transform(tab)
                } else {
                    tab
                }
            }
            state.copy(tabs = updatedTabs)
        }
    }

    // ==================== 嗅探处理 ====================

    fun onM3u8Sniffed(url: String, headers: Map<String, String>, source: SniffSource = SniffSource.DOM, tabId: String? = null) {
        val captured = getCapturedUrls(tabId)
        if (url.isBlank() || url in captured) return
        captured.add(url)

        // 设置嗅探状态为 SCANNING
        updateActiveTab(tabId) { it.copy(sniffState = SniffState.SCANNING, sniffProgress = "正在解析链接...") }
        // 启动超时检测
        startSniffTimeoutCheck(tabId)

        viewModelScope.launch {
            try {
                parseM3u8Link(url, null, headers, source, tabId)
                updateSniffResultState(tabId)
            } catch (e: Exception) {
                addLog("解析失败: ${e.message}")
                updateSniffResultState(tabId)
            }
        }
    }

    fun onNetworkM3u8Sniffed(url: String, tabId: String? = null) {
        val captured = getCapturedUrls(tabId)
        if (url.isBlank() || url in captured) return
        captured.add(url)

        val headers = extractHeadersFromUrl(url)

        // 设置嗅探状态为 SCANNING
        updateActiveTab(tabId) { it.copy(sniffState = SniffState.SCANNING, sniffProgress = "正在解析链接...") }
        // 启动超时检测
        startSniffTimeoutCheck(tabId)

        viewModelScope.launch {
            try {
                parseM3u8Link(url, null, headers, SniffSource.NETWORK, tabId)
                updateSniffResultState(tabId)
            } catch (e: Exception) {
                addLog("解析失败: ${e.message}")
                updateSniffResultState(tabId)
            }
        }
    }

    private suspend fun parseM3u8Link(
        url: String,
        content: String?,
        headers: Map<String, String>,
        source: SniffSource,
        tabId: String? = null
    ) {
        addLog("开始解析: ${url.substringAfterLast('/')}")

        // 获取当前嗅探结果数量，用于生成序号
        val currentIndex = _state.value.activeTabSniffedLinks.size

        try {
            val result = m3u8Parser.parse(url, headers)
            val m3u8Data = result.getOrThrow()

            // 尝试解析 variants 信息（从原始内容中）
            val fetchedContent = content ?: fetchContent(url, headers)
            val variants = if (fetchedContent != null && fetchedContent.contains("#EXT-X-STREAM-INF")) {
                parseVariants(fetchedContent, url)
            } else {
                emptyList()
            }

            val link = SniffedLink(
                url = url,
                fileName = generateFileName(url, currentIndex),
                headers = headers,
                duration = m3u8Data.segments.sumOf { it.duration },
                variants = variants,
                isPlaylist = variants.isNotEmpty(),
                source = source
            )

            addSniffedLink(link, tabId)
            addLog("解析成功: ${formatDuration(link.duration)}${if (variants.isNotEmpty()) "，${variants.size} 个分辨率" else ""}")

        } catch (e: Exception) {
            try {
                val fetchedContent = content ?: fetchContent(url, headers)
                if (fetchedContent != null && fetchedContent.contains("#EXT-X-STREAM-INF")) {
                    val variants = parseVariants(fetchedContent, url)
                    val link = SniffedLink(
                        url = url,
                        fileName = generateFileName(url, currentIndex),
                        headers = headers,
                        variants = variants,
                        isPlaylist = true,
                        source = source
                    )
                    addSniffedLink(link, tabId)
                    addLog("发现播放列表: ${variants.size} 个分辨率")
                } else if (fetchedContent != null && fetchedContent.contains("#EXTM3U")) {
                    val duration = parseDurationFromContent(fetchedContent)
                    val link = SniffedLink(
                        url = url,
                        fileName = generateFileName(url, currentIndex),
                        headers = headers,
                        duration = duration,
                        source = source
                    )
                    addSniffedLink(link, tabId)
                    addLog("解析成功: ${formatDuration(duration)}")
                } else {
                    addLog("非 M3U8 内容，跳过")
                }
            } catch (e2: Exception) {
                addLog("解析失败: ${e2.message}")
            }
        }
    }

    private fun addSniffedLink(link: SniffedLink, tabId: String? = null) {
        updateActiveTab(tabId) { tab ->
            tab.copy(sniffedLinks = tab.sniffedLinks + link)
        }
    }

    // ==================== 深度扫描 ====================

    fun triggerDeepScan() {
        jsExecutor?.invoke("CatCatchSniffer.deepScan()") ?: addLog("WebView 未就绪")
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
        val link = _state.value.activeTabSniffedLinks.find { it.url == url }
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

    fun toggleSnifferPanel() {
        _state.update { it.copy(isSnifferPanelOpen = !it.isSnifferPanelOpen) }
    }

    fun closeSnifferPanel() {
        _state.update { it.copy(isSnifferPanelOpen = false) }
    }

    fun setSniffMode(mode: SniffMode, persistForDomain: Boolean = false) {
        android.util.Log.d("CatCatch", "setSniffMode: ${mode.name}, persist=$persistForDomain")
        _state.update { it.copy(sniffMode = mode) }

        // 如果需要持久化到域名
        if (persistForDomain) {
            val currentUrl = _state.value.currentUrl
            if (currentUrl.isNotEmpty()) {
                viewModelScope.launch {
                    val bookmarks = bookmarkDao.getAllOnce()
                    val matching = bookmarks.find { domainsMatch(it.url, currentUrl) }
                    if (matching != null) {
                        bookmarkDao.updateSniffMode(matching.id, mode.name)
                        addLog("保存站点嗅探模式: ${mode.label}")
                    }
                }
            }
        }

        // 清除旧的嗅探结果和去重集合
        clearSniffedLinks()

        // 重置嗅探状态
        updateActiveTab { it.copy(sniffState = SniffState.SCANNING, sniffProgress = "正在嗅探...") }

        // 启动超时检测
        startSniffTimeoutCheck()

        // 通知 JS 层清除状态
        jsExecutor?.invoke("CatCatchSniffer.clearState()")

        // 重新加载页面以应用新的嗅探模式
        val currentUrl = _state.value.currentUrl
        if (currentUrl.isNotEmpty()) {
            webviewAction?.invoke("reload")
            addLog("重新加载页面以应用新模式: ${currentUrl.take(50)}...")

            // 延迟后重新初始化嗅探（等待页面加载完成）
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000) // 等待 3 秒
                val jsCmd = when (mode) {
                    SniffMode.AUTO -> "CatCatchSniffer.init()"
                    SniffMode.NETWORK -> "CatCatchSniffer.initNetworkOnly()"
                    SniffMode.DOM -> "CatCatchSniffer.initDomOnly()"
                    SniffMode.DEEP_SCAN -> "CatCatchSniffer.deepScan()"
                    SniffMode.DISGUISE -> "CatCatchSniffer.initDisguise()"
                }
                android.util.Log.d("CatCatch", "延迟执行JS: $jsCmd")
                jsExecutor?.invoke(jsCmd)
                addLog("重新初始化嗅探: ${mode.label}")
            }
        }

        addLog("切换嗅探模式: ${mode.label}")
    }

    // 新增: 重新嗅探当前模式（清除旧结果，重新加载页面）
    fun reSniff() {
        // 清除旧结果
        clearSniffedLinks()
        // 通知 JS 层清除状态
        jsExecutor?.invoke("CatCatchSniffer.clearState()")
        // 重新初始化当前模式
        val mode = _state.value.sniffMode
        updateActiveTab { it.copy(sniffState = SniffState.SCANNING, sniffProgress = "正在重新嗅探...") }

        // 启动超时检测
        startSniffTimeoutCheck()

        // 重新加载当前页面
        val currentUrl = _state.value.currentUrl
        if (currentUrl.isNotEmpty()) {
            webviewAction?.invoke("reload")
            addLog("重新加载页面: ${currentUrl.take(50)}...")

            // 延迟后重新初始化嗅探（等待页面加载完成）
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000) // 等待 3 秒
                val jsCmd = when (mode) {
                    SniffMode.AUTO -> "CatCatchSniffer.init()"
                    SniffMode.NETWORK -> "CatCatchSniffer.initNetworkOnly()"
                    SniffMode.DOM -> "CatCatchSniffer.initDomOnly()"
                    SniffMode.DEEP_SCAN -> "CatCatchSniffer.deepScan()"
                    SniffMode.DISGUISE -> "CatCatchSniffer.initDisguise()"
                }
                android.util.Log.d("CatCatch", "延迟执行JS: $jsCmd")
                jsExecutor?.invoke(jsCmd)
                addLog("重新初始化嗅探: ${mode.label}")
            }
        }

        addLog("重新嗅探: ${mode.label}")
    }

    // 新增: 停止嗅探
    fun stopSniffing() {
        updateActiveTab { it.copy(sniffState = SniffState.IDLE, sniffProgress = "") }
        addLog("停止嗅探")
    }

    // 新增: 启动嗅探超时检测
    private fun startSniffTimeoutCheck(tabId: String? = null) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(10000) // 10 秒超时
            val currentTab = _state.value.tabs.find { it.id == (tabId ?: _state.value.activeTabId) }
            if (currentTab != null && currentTab.sniffState == SniffState.SCANNING) {
                // 超时且仍在嗅探中，自动停止
                updateActiveTab(tabId) { tab ->
                    tab.copy(
                        sniffState = if (tab.sniffedLinks.isNotEmpty()) SniffState.FOUND else SniffState.EMPTY,
                        sniffProgress = if (tab.sniffedLinks.isNotEmpty()) "发现 ${tab.sniffedLinks.size} 个链接" else "未发现链接"
                    )
                }
                addLog("嗅探超时（10秒），自动停止")
            }
        }
    }

    // 新增: 更新嗅探结果状态
    private fun updateSniffResultState(tabId: String? = null) {
        updateActiveTab(tabId) { tab ->
            val count = tab.sniffedLinks.size
            tab.copy(
                sniffState = if (count > 0) SniffState.FOUND else SniffState.IDLE,
                sniffProgress = if (count > 0) "发现 $count 个链接" else ""
            )
        }

        // 自动模式嗅探到结果后，自动保存当前模式为站点默认
        val currentMode = _state.value.sniffMode
        if (currentMode == SniffMode.AUTO) {
            val currentUrl = _state.value.currentUrl
            if (currentUrl.isNotEmpty()) {
                val matchingBookmark = findBookmarkForDomain(currentUrl)
                if (matchingBookmark == null) {
                    // 没有绑定，自动保存当前模式为站点默认
                    viewModelScope.launch {
                        val bookmarks = bookmarkDao.getAllOnce()
                        val matching = bookmarks.find { domainsMatch(it.url, currentUrl) }
                        if (matching != null) {
                            bookmarkDao.updateSniffMode(matching.id, currentMode.name)
                            addLog("自动保存站点嗅探模式: ${currentMode.label}")
                        }
                    }
                }
            }
        }
    }

    fun selectVariant(linkUrl: String, variantIndex: Int) {
        updateActiveTab { tab ->
            val updatedLinks = tab.sniffedLinks.map { link ->
                if (link.url == linkUrl) link.copy(selectedVariantIndex = variantIndex) else link
            }
            tab.copy(sniffedLinks = updatedLinks)
        }
    }

    fun addAllTasks() {
        val links = _state.value.activeTabSniffedLinks
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
        getCapturedUrls(_state.value.activeTabId).clear()
        updateActiveTab { it.copy(sniffedLinks = emptyList(), sniffState = SniffState.IDLE, sniffProgress = "") }
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

    private fun generateFaviconUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return ""
            "https://s2.googleusercontent.com/s2/favicons?domain=$host&sz=64"
        } catch (e: Exception) {
            ""
        }
    }

    private fun generateFileName(url: String, index: Int = 0): String {
        val pageTitle = _state.value.pageTitle
        val baseName = if (pageTitle.isNotBlank()) {
            pageTitle.replace(Regex("[<>:\"/\\\\|?*]"), "_")
                .replace(Regex("\\s+"), "_")
                .take(50)
        } else {
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

            name.takeIf { it.isNotEmpty() && it.length > 2 } ?: "video_${System.currentTimeMillis() / 1000}"
        }

        // 如果有多个结果，添加序号
        return if (index > 0) "${baseName}_${index + 1}" else baseName
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

    // ==================== 域名匹配工具函数 ====================

    /**
     * 提取域名（去除 www 前缀）
     */
    private fun extractDomain(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return ""
            // 去除 www 前缀
            host.removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 域名匹配（支持子域名）
     */
    private fun domainsMatch(url1: String, url2: String): Boolean {
        val d1 = extractDomain(url1)
        val d2 = extractDomain(url2)
        if (d1.isEmpty() || d2.isEmpty()) return false
        return d1 == d2 || d1.endsWith(".$d2") || d2.endsWith(".$d1")
    }

    /**
     * 查找域名对应的书签（有嗅探模式绑定）
     */
    private fun findBookmarkForDomain(url: String): BookmarkEntity? {
        return _bookmarks.value.find { bm ->
            domainsMatch(bm.url, url) && bm.sniffMode.isNotEmpty()
        }
    }

    /**
     * 更新书签的嗅探模式绑定
     */
    fun updateBookmarkSniffMode(bookmarkId: Long, sniffMode: SniffMode?) {
        viewModelScope.launch {
            bookmarkDao.updateSniffMode(bookmarkId, sniffMode?.name ?: "")
            addLog("更新书签嗅探模式: ${sniffMode?.label ?: "默认"}")
        }
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

    // ==================== 标签页序列化 ====================

    private fun serializeTabs(tabs: List<Tab>, activeTabId: String): String {
        val root = org.json.JSONObject()
        val arr = org.json.JSONArray()
        for (tab in tabs) {
            if (tab.url.isEmpty()) continue
            val obj = org.json.JSONObject()
            obj.put("id", tab.id)
            obj.put("url", tab.url)
            obj.put("title", tab.title)
            obj.put("isBookmarked", tab.isBookmarked)
            arr.put(obj)
        }
        root.put("tabs", arr)
        root.put("activeTabId", activeTabId)
        return root.toString()
    }

    private fun deserializeTabs(json: String): Pair<List<Tab>, String>? {
        return try {
            val root = org.json.JSONObject(json)
            val arr = root.getJSONArray("tabs")
            val activeTabId = root.optString("activeTabId", "")
            val tabs = mutableListOf<Tab>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                tabs.add(
                    Tab(
                        id = obj.getString("id"),
                        url = obj.getString("url"),
                        title = obj.optString("title", ""),
                        isBookmarked = obj.optBoolean("isBookmarked", false)
                    )
                )
            }
            if (tabs.isEmpty()) return null
            val validActiveId = if (tabs.any { it.id == activeTabId }) activeTabId else tabs.first().id
            Pair(tabs, validActiveId)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 50
    }
}
