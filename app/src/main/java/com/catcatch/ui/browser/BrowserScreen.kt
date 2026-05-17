package com.catcatch.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.catcatch.ui.browser.config.SiteConfigs
import com.catcatch.ui.browser.model.SniffSource
import com.catcatch.ui.browser.model.Tab

/**
 * 浏览器页面
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // WebView 引用，用于后退/前进
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 加载 JS 脚本
    var snifferScript by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            snifferScript = context.assets.open("sniffer.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // 脚本加载失败
        }
    }

    // 成功/错误消息自动清除
    LaunchedEffect(state.success) {
        if (state.success != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearSuccess()
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    // 本地 URL 输入状态，避免频繁触发重组
    var localUrl by remember { mutableStateOf(state.currentUrl) }
    LaunchedEffect(state.currentUrl) {
        if (localUrl != state.currentUrl) {
            localUrl = state.currentUrl
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部地址栏（始终显示）
            AddressBar(
                tabCount = state.tabCount,
                url = localUrl,
                isLoading = state.isLoading,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                sniffedCount = state.sniffedLinks.size,
                isNewTab = state.mode == BrowserMode.NEW_TAB,
                isFavorite = state.isCurrentTabFavorite,
                onUrlChange = { newUrl ->
                    localUrl = newUrl
                    viewModel.onUrlChange(newUrl)
                },
                onLoad = {
                    focusManager.clearFocus()
                    viewModel.loadUrl(localUrl)
                },
                onHome = viewModel::goHome,
                onBack = { webViewRef?.goBack() },
                onForward = { webViewRef?.goForward() },
                onRefresh = { viewModel.loadUrl(localUrl) },
                onTabManager = viewModel::toggleTabManager,
                onDeepScan = { viewModel.triggerDeepScan() },
                onFavorite = { viewModel.toggleFavorite() }
            )

            // 加载进度条
            if (state.isLoading && state.mode == BrowserMode.BROWSING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 嗅探结果提示
            if (state.sniffedLinks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "发现 ${state.sniffedLinks.size} 个 M3U8 链接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = { viewModel.addTask(state.sniffedLinks.first()) }) {
                        Text("添加下载")
                    }
                }
            }

            // 内容区
            Box(modifier = Modifier.weight(1f)) {
                when (state.mode) {
                    BrowserMode.NEW_TAB -> {
                        NewTabContent()
                    }

                    BrowserMode.BROWSING -> {
                        CatCatchWebView(
                            url = state.currentUrl,
                            snifferScript = snifferScript,
                            viewModel = viewModel,
                            onWebViewCreated = { webViewRef = it },
                            onWebViewDisposed = { webViewRef = null }
                        )

                        if (state.isLoading) {
                            LoadingOverlay()
                        }
                    }

                    BrowserMode.TAB_MANAGER -> {}
                }
            }
        }

        // 悬浮嗅探按钮（仅在浏览模式显示，固定在顶层）
        if (state.mode == BrowserMode.BROWSING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                FloatingActionButton(
                    onClick = { viewModel.triggerDeepScan() },
                    containerColor = if (state.sniffedLinks.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "嗅探",
                        tint = if (state.sniffedLinks.isNotEmpty()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // 标签管理器覆盖层（带动画）- 修复动画问题
        AnimatedVisibility(
            visible = state.isTabManagerOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it })
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 半透明背景
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { viewModel.closeTabManager() }
                )

                // 标签管理面板
                Row(modifier = Modifier.fillMaxSize()) {
                    // 左侧面板（宽度 60%）
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.6f)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp)
                    ) {
                        // 标题
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "标签",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(onClick = { viewModel.newTab() }) {
                                Icon(Icons.Default.Add, "新建标签")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 标签列表
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(state.tabs, key = { it.id }) { tab ->
                                TabItem(
                                    tab = tab,
                                    isActive = tab.id == state.activeTabId,
                                    onClick = { viewModel.switchTab(tab.id) },
                                    onClose = { viewModel.closeTab(tab.id) }
                                )
                            }
                        }
                    }

                    // 右侧空白（点击关闭）- 添加 clickable 防止穿透
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                onClick = { viewModel.closeTabManager() },
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            )
                    )
                }
            }
        }

        // 消息提示
        state.success?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(message)
            }
        }

        state.error?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(message)
            }
        }
    }
}

// ==================== 地址栏（始终显示）====================

@Composable
private fun AddressBar(
    tabCount: Int,
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    sniffedCount: Int,
    isNewTab: Boolean,
    isFavorite: Boolean,
    onUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onTabManager: () -> Unit,
    onDeepScan: () -> Unit,
    onFavorite: () -> Unit
) {
    val tabButtonSize = 32.dp
    val navButtonSize = 44.dp
    val navIconSize = 24.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标签管理按钮（最左侧，带黑色边框，缩小）
        Box(
            modifier = Modifier
                .size(tabButtonSize)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.5.dp,
                    color = Color.Black,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable { onTabManager() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$tabCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 主页按钮（大一些）
        IconButton(
            onClick = onHome,
            modifier = Modifier.size(navButtonSize)
        ) {
            Icon(
                Icons.Default.Home,
                "主页",
                modifier = Modifier.size(navIconSize)
            )
        }

        // 收藏按钮（五角星，点击后为黑色）
        IconButton(
            onClick = onFavorite,
            modifier = Modifier.size(navButtonSize)
        ) {
            Icon(
                if (isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                "收藏",
                modifier = Modifier.size(navIconSize),
                tint = if (isFavorite) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // URL 输入框（带发送/刷新按钮）
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (url.isEmpty()) {
                            Text(
                                "输入网址",
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 发送/刷新按钮
            IconButton(
                onClick = if (isNewTab) onLoad else onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isNewTab) Icons.AutoMirrored.Filled.Send else Icons.Default.Refresh,
                        contentDescription = if (isNewTab) "跳转" else "刷新",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 后退按钮（大一些）
        IconButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.size(navButtonSize)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "后退",
                modifier = Modifier.size(navIconSize)
            )
        }

        // 前进按钮（大一些）
        IconButton(
            onClick = onForward,
            enabled = canGoForward,
            modifier = Modifier.size(navButtonSize)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                "前进",
                modifier = Modifier.size(navIconSize)
            )
        }
    }
}

// ==================== 新标签页内容 ====================

@Composable
private fun NewTabContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "CatCatch",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "输入网址开始浏览",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 加载遮罩 ====================

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "正在加载...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ==================== 标签项 ====================

@Composable
private fun TabItem(
    tab: Tab,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tab.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!tab.isNewTab) {
                Text(
                    tab.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.Close,
                "关闭",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ==================== WebView ====================

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CatCatchWebView(
    url: String,
    snifferScript: String,
    viewModel: BrowserViewModel,
    onWebViewCreated: (WebView) -> Unit,
    onWebViewDisposed: () -> Unit
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                addJavascriptInterface(
                    SnifferBridge(
                        onM3u8Found = { m3u8Url, headers ->
                            viewModel.onM3u8Sniffed(m3u8Url, headers, SniffSource.DOM)
                        },
                        onLog = { message ->
                            viewModel.addLog("[JS] $message")
                        },
                        onTitle = { title ->
                            viewModel.onPageTitleChanged(title)
                        }
                    ),
                    SnifferBridge.BRIDGE_NAME
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        if (requestUrl.contains(".m3u8", ignoreCase = true)) {
                            viewModel.onNetworkM3u8Sniffed(requestUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val requestUrl = request.url.toString()
                        if (SiteConfigs.isAdUrl(requestUrl)) {
                            viewModel.addLog("拦截广告: ${requestUrl.take(50)}...")
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { viewModel.onPageStarted(it) }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { viewModel.onPageFinished(it) }
                        viewModel.updateNavigationState(view.canGoBack(), view.canGoForward())
                        view.title?.let { title ->
                            viewModel.onPageTitleChanged(title)
                        }
                        if (snifferScript.isNotEmpty()) {
                            view.evaluateJavascript(snifferScript, null)
                        }
                    }
                }

                // 通知 WebView 创建
                onWebViewCreated(this)

                if (url.isNotEmpty()) {
                    loadUrl(url)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            if (url.isNotEmpty() && view.url != url) {
                view.loadUrl(url)
            }
        },
        onReset = { view ->
            // WebView 被移除时清理
            view.destroy()
            onWebViewDisposed()
        }
    )
}
