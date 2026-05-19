package com.catcatch.ui.browser

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarOutline
import coil.compose.AsyncImage
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.catcatch.data.local.BookmarkEntity
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
    val bookmarks by viewModel.bookmarks.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // 同步加载 JS 脚本（避免 LaunchedEffect 异步导致首次页面加载时脚本为空）
    val snifferScript = remember {
        try {
            val script = context.assets.open("sniffer.js").bufferedReader().use { it.readText() }
            android.util.Log.d("CatCatch", "sniffer.js 同步加载成功, 长度=${script.length}")
            script
        } catch (e: Exception) {
            android.util.Log.e("CatCatch", "sniffer.js 加载失败", e)
            ""
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

    // 本地 URL 输入状态
    var localUrl by remember { mutableStateOf(state.currentUrl) }
    var isEditing by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentUrl) {
        if (!isEditing && localUrl != state.currentUrl) {
            localUrl = state.currentUrl
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部地址栏
            AddressBar(
                tabCount = state.tabCount,
                url = localUrl,
                isLoading = state.isLoading,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                isNewTab = state.mode == BrowserMode.NEW_TAB,
                isBookmarked = state.isCurrentTabBookmarked,
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
                onBookmark = { viewModel.toggleBookmark() },
                onBookmarkManager = { viewModel.toggleBookmarkManager() }
            )

            // 加载进度条
            if (state.isLoading && state.mode == BrowserMode.BROWSING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 嗅探结果提示条（动态显示进度）
            if (state.isSniffing || state.activeTabSniffedLinks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (state.isSniffing) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .clickable(enabled = state.activeTabSniffedLinks.isNotEmpty()) {
                            viewModel.toggleSnifferPanel()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isSniffing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (state.isSniffing) {
                                state.sniffProgress.ifEmpty { "正在嗅探..." }
                            } else {
                                "发现 ${state.activeTabSniffedLinks.size} 个 M3U8 链接"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.isSniffing) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                    if (state.activeTabSniffedLinks.isNotEmpty()) {
                        Text(
                            "点击查看详情",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 内容区
            Box(modifier = Modifier.weight(1f)) {
                CatCatchWebView(
                    tabs = state.tabs,
                    activeTabId = state.activeTabId,
                    snifferScript = snifferScript,
                    viewModel = viewModel,
                    sslStrictMode = state.sslStrictMode
                )

                // 新标签页遮罩
                if (state.mode != BrowserMode.BROWSING) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        NewTabContent(
                            bookmarks = bookmarks,
                            onBookmarkClick = { url -> viewModel.loadUrl(url) },
                            onBookmarkDelete = { url -> viewModel.removeBookmark(url) }
                        )
                    }
                }
            }
        }

        // 悬浮嗅探按钮
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            FloatingActionButton(
                onClick = { viewModel.toggleSnifferPanel() },  // 始终打开面板
                containerColor = when {
                    state.isSniffing -> MaterialTheme.colorScheme.tertiary
                    state.activeTabSniffedLinks.isNotEmpty() -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                if (state.isSniffing) {
                    // 嗅探中：显示旋转动画
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiary
                    )
                } else {
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
        }

        // 标签管理器覆盖层
        AnimatedVisibility(
            visible = state.isTabManagerOpen,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { -it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 3 })
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { viewModel.closeTabManager() }
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.33f)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp)
                    ) {
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
                            Row {
                                IconButton(onClick = { viewModel.closeAllTabs() }) {
                                    Icon(Icons.Default.DeleteSweep, "清除所有标签")
                                }
                                IconButton(onClick = { viewModel.newTab() }) {
                                    Icon(Icons.Default.Add, "新建标签")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

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

        // 书签管理器覆盖层
        AnimatedVisibility(
            visible = state.isBookmarkManagerOpen,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 })
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { viewModel.closeBookmarkManager() }
                )

                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
                    // 右侧空白（点击关闭）
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .clickable(
                                onClick = { viewModel.closeBookmarkManager() },
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            )
                    )

                    // 右侧面板（宽度 50%）
                    BookmarkManagerPanel(
                        bookmarks = bookmarks,
                        onClose = { viewModel.closeBookmarkManager() },
                        onBookmarkClick = { url -> viewModel.loadBookmark(url) },
                        onBookmarkDelete = { id -> viewModel.removeBookmarkById(id) },
                        onBookmarksDelete = { ids -> viewModel.deleteSelectedBookmarks(ids) },
                        onBookmarkAdd = { title, url -> viewModel.addBookmark(title, url) },
                        onBookmarkSniffModeChange = { id, mode -> viewModel.updateBookmarkSniffMode(id, mode) }
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
                        isSniffing = state.isSniffing,
                        onSelectVariant = { url, index -> viewModel.selectVariant(url, index) },
                        onAddTask = { link -> viewModel.addTask(link) },
                        onSniffModeChange = { mode -> viewModel.setSniffMode(mode) },
                        onReSniff = { viewModel.reSniff() },
                        onStopSniffing = { viewModel.stopSniffing() },
                        onSaveAsDomainDefault = { mode -> viewModel.setSniffMode(mode, persistForDomain = true) }
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

        // 长按链接上下文菜单
        state.linkContextMenuUrl?.let { url ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissLinkContextMenu() },
                title = {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column {
                        // 在新标签页中打开
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.openLinkInNewTab(url) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("在新标签页中打开", style = MaterialTheme.typography.bodyLarge)
                        }
                        // 复制链接
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                                    viewModel.dismissLinkContextMenu()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("复制链接", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

// ==================== 地址栏 ====================

@Composable
private fun AddressBar(
    tabCount: Int,
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isNewTab: Boolean,
    isBookmarked: Boolean,
    onUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onTabManager: () -> Unit,
    onBookmark: () -> Unit,
    onBookmarkManager: () -> Unit
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

        // URL 输入框
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

        // 后退按钮
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

        // 前进按钮
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

        // 书签按钮（五角星）- 移动到后退按钮左侧
        IconButton(
            onClick = onBookmark,
            modifier = Modifier.size(navButtonSize)
        ) {
            Icon(
                if (isBookmarked) Icons.Default.Star else Icons.Outlined.StarOutline,
                "书签",
                modifier = Modifier.size(navIconSize),
                tint = if (isBookmarked) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
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

        // 书签管理按钮（使用 Bookmark 图标）
        IconButton(
            onClick = onBookmarkManager,
            modifier = Modifier.size(navButtonSize)
        ) {
            Icon(
                Icons.Outlined.BookmarkBorder,
                "书签管理",
                modifier = Modifier.size(navIconSize)
            )
        }
    }
}

// ==================== 新标签页内容 ====================

@Composable
private fun NewTabContent(
    bookmarks: List<BookmarkEntity>,
    onBookmarkClick: (String) -> Unit,
    onBookmarkDelete: (String) -> Unit
) {
    var bookmarkToDelete by remember { mutableStateOf<BookmarkEntity?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (bookmarks.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bookmarks) { bookmark ->
                    BookmarkGridItem(
                        bookmark = bookmark,
                        onClick = { onBookmarkClick(bookmark.url) },
                        onLongClick = { bookmarkToDelete = bookmark }
                    )
                }
            }
        } else {
            Text(
                "收藏网站后会在这里显示",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    // 删除确认对话框
    bookmarkToDelete?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { bookmarkToDelete = null },
            title = { Text("删除书签") },
            text = { Text("确定删除「${bookmark.title}」？") },
            confirmButton = {
                TextButton(onClick = {
                    onBookmarkDelete(bookmark.url)
                    bookmarkToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkGridItem(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
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
            if (bookmark.faviconUrl.isNotEmpty()) {
                AsyncImage(
                    model = bookmark.faviconUrl,
                    contentDescription = bookmark.title,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                val host = try {
                    android.net.Uri.parse(bookmark.url).host?.take(1)?.uppercase() ?: ""
                } catch (e: Exception) {
                    ""
                }
                if (host.isNotEmpty()) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = bookmark.title,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 标题
        Text(
            text = bookmark.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ==================== 书签管理面板 ====================

@Composable
private fun BookmarkManagerPanel(
    bookmarks: List<BookmarkEntity>,
    onClose: () -> Unit,
    onBookmarkClick: (String) -> Unit,
    onBookmarkDelete: (Long) -> Unit,
    onBookmarksDelete: (Set<Long>) -> Unit,
    onBookmarkAdd: (String, String) -> Unit,
    onBookmarkSniffModeChange: (Long, SniffMode?) -> Unit
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.5f)
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "书签管理",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "关闭")
            }
        }

        // 操作栏（根据模式切换）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                // 多选模式：显示已选数量和操作按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已选 ${selectedIds.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 全选/取消全选
                    TextButton(
                        onClick = {
                            selectedIds = if (selectedIds.size == bookmarks.size) {
                                emptySet()
                            } else {
                                bookmarks.map { it.id }.toSet()
                            }
                        }
                    ) {
                        Text(
                            if (selectedIds.size == bookmarks.size) "取消全选" else "全选",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row {
                    TextButton(
                        onClick = {
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                showDeleteConfirmDialog = true
                            }
                        },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Text(
                            "删除",
                            color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 普通模式：显示新增和批量管理按钮
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新增")
                }
                TextButton(onClick = { isSelectionMode = true }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("多选")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 书签列表
        if (bookmarks.isNotEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    BookmarkItem(
                        bookmark = bookmark,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedIds.contains(bookmark.id),
                        onClick = {
                            if (isSelectionMode) {
                                selectedIds = if (selectedIds.contains(bookmark.id)) {
                                    selectedIds - bookmark.id
                                } else {
                                    selectedIds + bookmark.id
                                }
                            } else {
                                onBookmarkClick(bookmark.url)
                            }
                        },
                        onDelete = { onBookmarkDelete(bookmark.id) },
                        onSniffModeChange = onBookmarkSniffModeChange
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无书签",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 新增书签对话框
    if (showAddDialog) {
        AddBookmarkDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, url ->
                onBookmarkAdd(title, url)
                showAddDialog = false
            }
        )
    }

    // 批量删除确认对话框
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("批量删除书签") },
            text = { Text("确定删除选中的 ${selectedIds.size} 个书签？") },
            confirmButton = {
                TextButton(onClick = {
                    onBookmarksDelete(selectedIds)
                    selectedIds = emptySet()
                    isSelectionMode = false
                    showDeleteConfirmDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ==================== 新增书签对话框 ====================

@Composable
private fun AddBookmarkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增书签") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, url) },
                enabled = title.isNotBlank() && url.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ==================== 书签管理项 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkItem(
    bookmark: BookmarkEntity,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSniffModeChange: (Long, SniffMode?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选框（多选模式下显示）
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        // Favicon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (bookmark.faviconUrl.isNotEmpty()) {
                AsyncImage(
                    model = bookmark.faviconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                val host = try {
                    android.net.Uri.parse(bookmark.url).host?.take(1)?.uppercase() ?: ""
                } catch (e: Exception) {
                    ""
                }
                if (host.isNotEmpty()) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 书签信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                bookmark.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                bookmark.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.outline
            )
        }

        // 嗅探模式选择下拉菜单
        if (!isSelectionMode) {
            var sniffModeDropdownExpanded by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { sniffModeDropdownExpanded = true },
                    label = {
                        Text(
                            if (bookmark.sniffMode.isNotEmpty()) {
                                try { SniffMode.valueOf(bookmark.sniffMode).label } catch (_: Exception) { "默认" }
                            } else "默认",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                DropdownMenu(
                    expanded = sniffModeDropdownExpanded,
                    onDismissRequest = { sniffModeDropdownExpanded = false }
                ) {
                    // 默认选项（清除绑定，使用默认 AUTO）
                    DropdownMenuItem(
                        text = { Text("默认") },
                        onClick = {
                            onSniffModeChange(bookmark.id, null)
                            sniffModeDropdownExpanded = false
                        }
                    )
                    // 各种嗅探模式
                    SniffMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                onSniffModeChange(bookmark.id, mode)
                                sniffModeDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // 删除按钮（非选择模式时显示）
        if (!isSelectionMode) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
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

// ==================== 嗅探结果弹窗内容 ====================

@Composable
private fun SnifferPanelContent(
    links: List<com.catcatch.ui.browser.model.SniffedLink>,
    sniffMode: SniffMode,
    isSniffing: Boolean,
    onSelectVariant: (String, Int) -> Unit,
    onAddTask: (com.catcatch.ui.browser.model.SniffedLink) -> Unit,
    onSniffModeChange: (SniffMode) -> Unit,
    onReSniff: () -> Unit,
    onStopSniffing: () -> Unit,
    onSaveAsDomainDefault: (SniffMode) -> Unit
) {
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    Column {
        // 嗅探模式切换 + 重新嗅探按钮
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 重新嗅探按钮（文字按钮）
                TextButton(
                    onClick = if (isSniffing) onStopSniffing else onReSniff
                ) {
                    if (isSniffing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("停止嗅探", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重新嗅探", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // 模式切换
                Box {
                    AssistChip(
                        onClick = { modeDropdownExpanded = true },
                        label = { Text(sniffMode.label) }
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
        }

        // 保存为站点默认按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { onSaveAsDomainDefault(sniffMode) },
                enabled = !isSniffing
            ) {
                Icon(Icons.Default.Bookmark, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存为站点默认", style = MaterialTheme.typography.bodySmall)
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

        // 空状态显示
        if (links.isEmpty() && !isSniffing) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "未发现 M3U8 链接",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onReSniff) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重新嗅探")
                }
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
                    com.catcatch.ui.browser.model.SniffSource.PLAYER -> MaterialTheme.colorScheme.tertiaryContainer
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

            // 第二行：时长 + 文件大小 + 编码 + 变体信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 新增: 估算文件大小或时长
                if (link.estimatedSizeText != "未知") {
                    Text(
                        link.estimatedSizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 新增: 编码格式
                if (link.selectedCodecDisplay.isNotEmpty()) {
                    Text(
                        link.selectedCodecDisplay,
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

            // 第三行：变体选择
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
                                        "${variant.resolutionText} · ${variant.codecDisplay} · ${variant.metaText}",
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
    viewModel: BrowserViewModel,
    sslStrictMode: Boolean = true
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
                "reload" -> webViews[activeTabId]?.reload()
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

    // 容器 + 子视图管理
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

            for (tab in tabs) {
                if (tab.id !in webViews && tab.url.isNotEmpty()) {
                    val webView = createWebView(context, tab.id, snifferScript, viewModel, sslStrictMode)
                    webViews[tab.id] = webView
                    container.addView(webView)
                }
            }

            for ((id, view) in webViews) {
                view.visibility = if (id == activeTabId) android.view.View.VISIBLE else android.view.View.GONE
            }
            webViews[activeTabId]?.let { container.bringChildToFront(it) }

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
    viewModel: BrowserViewModel,
    sslStrictMode: Boolean = true
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
            userAgentString = android.webkit.WebSettings.getDefaultUserAgent(context)
                .replace("; wv)", ")")
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

        setOnLongClickListener {
            val hitTestResult = hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                val url = hitTestResult.extra
                if (!url.isNullOrEmpty()) {
                    viewModel.showLinkContextMenu(url)
                    true
                } else false
            } else false
        }

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
                view: WebView, handler: SslErrorHandler, error: SslError
            ) {
                if (sslStrictMode) {
                    viewModel.addLog("[SSL] 证书错误，已拦截: ${error.url?.take(80)}")
                    handler.cancel()
                } else {
                    viewModel.addLog("[SSL] 证书错误，已忽略: ${error.url?.take(80)}")
                    handler.proceed()
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    viewModel.addLog("[错误] ${error.errorCode}: ${error.description} | ${request.url?.toString()?.take(80)}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    viewModel.addLog("[HTTP ${errorResponse.statusCode}] ${request.url?.toString()?.take(80)}")
                }
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
                    val currentMode = viewModel.state.value.sniffMode
                    val initScript = when (currentMode) {
                        com.catcatch.ui.browser.SniffMode.AUTO -> "CatCatchSniffer.init()"
                        com.catcatch.ui.browser.SniffMode.NETWORK -> "CatCatchSniffer.initNetworkOnly()"
                        com.catcatch.ui.browser.SniffMode.DOM -> "CatCatchSniffer.initDomOnly()"
                        com.catcatch.ui.browser.SniffMode.DEEP_SCAN -> "CatCatchSniffer.deepScan()"
                        com.catcatch.ui.browser.SniffMode.DISGUISE -> "CatCatchSniffer.initDisguise()"
                    }
                    android.util.Log.d("CatCatch", "onPageFinished: mode=$currentMode, script=$initScript, snifferLen=${snifferScript.length}")
                    view.evaluateJavascript(initScript, null)
                } else {
                    android.util.Log.w("CatCatch", "onPageFinished: snifferScript为空! mode=${viewModel.state.value.sniffMode}")
                }
            }
        }
    }
}
