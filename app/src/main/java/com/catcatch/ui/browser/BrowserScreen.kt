package com.catcatch.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import coil.compose.AsyncImage
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
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
    val shortcuts by viewModel.shortcuts.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

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
    var isEditing by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentUrl) {
        if (!isEditing && localUrl != state.currentUrl) {
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
                sniffedCount = state.activeTabSniffedLinks.size,
                isNewTab = state.mode == BrowserMode.NEW_TAB,
                isFavorite = state.isCurrentTabFavorite,
                onUrlChange = { newUrl ->
                    isEditing = true
                    localUrl = newUrl
                },
                onLoad = {
                    isEditing = false
                    focusManager.clearFocus()
                    viewModel.loadUrl(localUrl)
                },
                onHome = {
                    localUrl = ""
                    viewModel.goHome()
                },
                onBack = { viewModel.goBack() },
                onForward = { viewModel.goForward() },
                onRefresh = { viewModel.loadUrl(localUrl) },
                onTabManager = viewModel::toggleTabManager,
                onDeepScan = { viewModel.triggerDeepScan() },
                onFavorite = { viewModel.toggleFavorite() }
            )

            // 加载进度条
            if (state.isLoading && state.mode == BrowserMode.BROWSING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 嗅探结果提示条（可点击展开面板）
            if (state.activeTabSniffedLinks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { viewModel.toggleSnifferPanel() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "发现 ${state.activeTabSniffedLinks.size} 个 M3U8 链接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "点击查看详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // 内容区：WebView 始终保持在 composition 中，避免切换模式时丢失状态
            Box(modifier = Modifier.weight(1f)) {
                // WebView 始终存在，切换模式时不销毁
                CatCatchWebView(
                    tabs = state.tabs,
                    activeTabId = state.activeTabId,
                    snifferScript = snifferScript,
                    viewModel = viewModel
                )

                // 非浏览模式时覆盖遮罩
                if (state.mode != BrowserMode.BROWSING) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        NewTabContent(
                            shortcuts = shortcuts,
                            onShortcutClick = { url -> viewModel.loadUrl(url) },
                            onShortcutDelete = { url -> viewModel.removeShortcut(url) }
                        )
                    }
                }
            }
        }

        // 悬浮嗅探按钮（所有页面可见）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            FloatingActionButton(
                onClick = {
                    if (state.activeTabSniffedLinks.isNotEmpty()) {
                        viewModel.toggleSnifferPanel()
                    } else {
                        viewModel.triggerDeepScan()
                    }
                },
                containerColor = if (state.activeTabSniffedLinks.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "嗅探",
                    tint = if (state.activeTabSniffedLinks.isNotEmpty()) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // 标签管理器覆盖层（带动画）
        AnimatedVisibility(
            visible = state.isTabManagerOpen,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { -it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 3 })
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
                    // 左侧面板（宽度 33%）
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.33f)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp)
                    ) {
                        // 标题
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "标签",
                                style = MaterialTheme.typography.titleMedium,
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

        // 嗅探结果弹窗
        if (state.isSnifferPanelOpen) {
            AlertDialog(
                onDismissRequest = { viewModel.closeSnifferPanel() },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("嗅探结果")
                        TextButton(onClick = { viewModel.addAllTasks() }) {
                            Text("全部添加")
                        }
                    }
                },
                text = {
                    SnifferPanelContent(
                        links = state.activeTabSniffedLinks,
                        sniffMode = state.sniffMode,
                        onSelectVariant = { url, index -> viewModel.selectVariant(url, index) },
                        onAddTask = { link -> viewModel.addTask(link) },
                        onSniffModeChange = { mode -> viewModel.setSniffMode(mode) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.closeSnifferPanel() }) {
                        Text("关闭")
                    }
                },
                shape = RoundedCornerShape(16.dp)
            )
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
    val tabButtonSize = 36.dp
    val navButtonSize = 44.dp
    val navIconSize = 24.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标签管理按钮
        Box(
            modifier = Modifier
                .size(tabButtonSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .clickable { onTabManager() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$tabCount",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onLoad() }),
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

        // 主页按钮
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
    }
}

// ==================== 新标签页内容 ====================

@Composable
private fun NewTabContent(
    shortcuts: List<com.catcatch.data.local.ShortcutEntity>,
    onShortcutClick: (String) -> Unit,
    onShortcutDelete: (String) -> Unit
) {
    // 待删除的快捷方式
    var shortcutToDelete by remember { mutableStateOf<com.catcatch.data.local.ShortcutEntity?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (shortcuts.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shortcuts) { shortcut ->
                    ShortcutItem(
                        shortcut = shortcut,
                        onClick = { onShortcutClick(shortcut.url) },
                        onLongClick = { shortcutToDelete = shortcut }
                    )
                }
            }
        } else {
            // 空状态提示
            Text(
                "收藏网站后会在这里显示",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    // 删除确认对话框
    shortcutToDelete?.let { shortcut ->
        AlertDialog(
            onDismissRequest = { shortcutToDelete = null },
            title = { Text("删除快捷方式") },
            text = { Text("确定删除「${shortcut.title}」？") },
            confirmButton = {
                TextButton(onClick = {
                    onShortcutDelete(shortcut.url)
                    shortcutToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { shortcutToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutItem(
    shortcut: com.catcatch.data.local.ShortcutEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val host = try {
        android.net.Uri.parse(shortcut.url).host ?: ""
    } catch (e: Exception) {
        ""
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Favicon 图标
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (host.isNotEmpty()) {
                AsyncImage(
                    model = "https://favicon.im/$host",
                    contentDescription = shortcut.title,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Icon(
                    Icons.Default.Public,
                    contentDescription = shortcut.title,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 标题
        Text(
            text = shortcut.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
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

// ==================== 嗅探结果弹窗内容 ====================

@Composable
private fun SnifferPanelContent(
    links: List<com.catcatch.ui.browser.model.SniffedLink>,
    sniffMode: SniffMode,
    onSelectVariant: (String, Int) -> Unit,
    onAddTask: (com.catcatch.ui.browser.model.SniffedLink) -> Unit,
    onSniffModeChange: (SniffMode) -> Unit
) {
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    Column {
        // 嗅探模式切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "嗅探模式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                AssistChip(
                    onClick = { modeDropdownExpanded = true },
                    label = { Text(sniffMode.label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                DropdownMenu(
                    expanded = modeDropdownExpanded,
                    onDismissRequest = { modeDropdownExpanded = false }
                ) {
                    SniffMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                onSniffModeChange(mode)
                                modeDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "共 ${links.size} 个链接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 可滚动链接列表
        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(links, key = { it.url }) { link ->
                SniffedLinkItem(
                    link = link,
                    onSelectVariant = { index -> onSelectVariant(link.url, index) },
                    onAdd = { onAddTask(link) }
                )
            }
        }
    }
}

// ==================== 嗅探链接卡片 ====================

@Composable
private fun SniffedLinkItem(
    link: com.catcatch.ui.browser.model.SniffedLink,
    onSelectVariant: (Int) -> Unit,
    onAdd: () -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：文件名 + 来源标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    link.fileName.ifEmpty { link.url.substringAfterLast("/").take(30) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 来源标签
                val sourceColor = when (link.source) {
                    com.catcatch.ui.browser.model.SniffSource.NETWORK -> MaterialTheme.colorScheme.tertiary
                    com.catcatch.ui.browser.model.SniffSource.DOM -> MaterialTheme.colorScheme.primary
                    com.catcatch.ui.browser.model.SniffSource.DEEP_SCAN -> MaterialTheme.colorScheme.secondary
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(sourceColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        link.source.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = sourceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 第二行：时长 + 变体信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (link.duration > 0) {
                    Text(
                        com.catcatch.ui.browser.model.formatDuration(link.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (link.variants.isNotEmpty()) {
                    Text(
                        "${link.variants.size} 个分辨率",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 第三行：变体选择（有变体时）
            if (link.hasVariants) {
                Spacer(modifier = Modifier.height(8.dp))

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val selected = link.variants.getOrElse(link.selectedVariantIndex) { link.variants.first() }
                        Text(
                            "${selected.resolutionText} · ${selected.bandwidthText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "选择分辨率",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        link.variants.forEachIndexed { index, variant ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${variant.resolutionText} · ${variant.metaText}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                onClick = {
                                    onSelectVariant(index)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 底部：添加下载按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onAdd) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加下载")
                }
            }
        }
    }
}

// ==================== WebView（多实例管理）====================

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CatCatchWebView(
    tabs: List<Tab>,
    activeTabId: String,
    snifferScript: String,
    viewModel: BrowserViewModel
) {
    val webViews = remember { mutableMapOf<String, WebView>() }
    val context = LocalContext.current

    // 注册 JS 执行和导航回调
    SideEffect {
        viewModel.registerJsExecutor { script ->
            webViews[activeTabId]?.evaluateJavascript(script, null)
        }
        viewModel.registerWebviewAction { action ->
            when (action) {
                "back" -> webViews[activeTabId]?.goBack()
                "forward" -> webViews[activeTabId]?.goForward()
            }
        }
    }

    // 为活跃标签加载 URL
    val currentTab = tabs.find { it.id == activeTabId }
    LaunchedEffect(activeTabId, currentTab?.url) {
        val webView = webViews[activeTabId] ?: return@LaunchedEffect
        val tab = currentTab ?: return@LaunchedEffect
        if (tab.url.isNotEmpty() && webView.url != tab.url) {
            webView.loadUrl(tab.url)
        }
    }

    // 容器 + 子视图管理（update 回调有容器直接引用，适合增删子视图）
    AndroidView(
        factory = { ctx ->
            android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { container ->
            val tabIds = tabs.map { it.id }.toSet()

            // 创建有 URL 的标签的 WebView（空标签不创建，避免 MIUI 拦截器崩溃）
            for (tab in tabs) {
                if (tab.id !in webViews && tab.url.isNotEmpty()) {
                    val webView = createWebView(context, tab.id, snifferScript, viewModel)
                    webViews[tab.id] = webView
                    container.addView(webView)
                }
            }

            // 切换可见性：activeTab 可见，其他隐藏
            for ((id, view) in webViews) {
                view.visibility = if (id == activeTabId) android.view.View.VISIBLE else android.view.View.GONE
            }
            // 将活跃标签置于最前
            webViews[activeTabId]?.let { container.bringChildToFront(it) }

            // 销毁已关闭标签的 WebView
            val toRemove = webViews.keys.filter { it !in tabIds }
            for (id in toRemove) {
                webViews[id]?.let { webView ->
                    container.removeView(webView)
                    webView.destroy()
                }
                webViews.remove(id)
            }
        }
    )

    // Composable 销毁时清理
    DisposableEffect(Unit) {
        onDispose {
            viewModel.registerJsExecutor(null)
            viewModel.registerWebviewAction(null)
            webViews.values.forEach { webView ->
                try {
                    webView.webViewClient = WebViewClient()
                    (webView.parent as? android.widget.FrameLayout)?.removeView(webView)
                    webView.stopLoading()
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Exception) {}
            }
            webViews.clear()
        }
    }
}

private fun createWebView(
    context: android.content.Context,
    tabId: String,
    snifferScript: String,
    viewModel: BrowserViewModel
): WebView {
    return WebView(context).apply {
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
                m3u8FoundCallback = { m3u8Url, headers ->
                    viewModel.onM3u8Sniffed(m3u8Url, headers, SniffSource.DOM, tabId)
                },
                logCallback = { message -> viewModel.addLog("[JS] $message") },
                titleCallback = { title -> viewModel.onPageTitleChanged(title, tabId) }
            ),
            SnifferBridge.BRIDGE_NAME
        )

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.url.toString().contains(".m3u8", ignoreCase = true)) {
                    viewModel.onNetworkM3u8Sniffed(request.url.toString(), tabId)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val requestUrl = request.url.toString()
                if (SiteConfigs.isAdUrl(requestUrl)) {
                    viewModel.addLog("拦截广告: ${requestUrl.take(50)}...")
                    return true
                }
                return false
            }

            override fun onReceivedSslError(
                view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError
            ) {
                // 视频网站常有证书问题，允许继续访问
                handler.proceed()
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { viewModel.onPageStarted(it, tabId) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                url?.let { viewModel.onPageFinished(it, tabId) }
                viewModel.updateNavigationState(view.canGoBack(), view.canGoForward(), tabId)
                view.title?.let { viewModel.onPageTitleChanged(it, tabId) }
                if (snifferScript.isNotEmpty()) {
                    view.evaluateJavascript(snifferScript, null)
                }
            }
        }
    }
}
