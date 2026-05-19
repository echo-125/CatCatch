package com.catcatch.ui.browser.model

import com.catcatch.ui.browser.SniffState
import java.util.UUID

/**
 * 浏览器标签页
 */
data class Tab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "新标签页",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isBookmarked: Boolean = false,
    val sniffedLinks: List<SniffedLink> = emptyList(),
    val sniffState: SniffState = SniffState.IDLE,
    val sniffProgress: String = ""
) {
    /**
     * 是否是新标签页（URL 为空）
     */
    val isNewTab: Boolean
        get() = url.isEmpty()

    /**
     * 获取显示用的标题
     */
    val displayTitle: String
        get() = if (isNewTab) "新标签页" else title.ifEmpty { url }
}
