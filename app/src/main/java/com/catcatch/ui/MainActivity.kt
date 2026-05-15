package com.catcatch.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.catcatch.CatCatchApp
import com.catcatch.DeepLinkData
import com.catcatch.data.repository.SettingsRepository
import com.catcatch.ui.navigation.MainScreen
import com.catcatch.ui.theme.CatCatchTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 主 Activity
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理 Deep Link
        handleDeepLink(intent)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val darkMode by settingsRepository.darkMode.collectAsState(initial = 0)

            val darkTheme = when (darkMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            CatCatchTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(windowSizeClass = windowSizeClass)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * 处理 Deep Link Intent
     * 支持: catcatch://add?url=...&title=...&headers=...&silent=true
     * 支持: ACTION_SEND（浏览器分享）
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return

        // 处理 ACTION_SEND（浏览器分享）
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extractUrl(sharedText)
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
                val app = application as CatCatchApp
                app.pendingDeepLink = DeepLinkData(url, title, emptyMap(), false)
            }
            return
        }

        // 处理 Deep Link URI
        val uri = intent.data ?: return

        when (uri.scheme) {
            "catcatch" -> {
                if (uri.host != "add") return
                val url = uri.getQueryParameter("url") ?: return
                if (!url.startsWith("http://") && !url.startsWith("https://")) return
                val title = uri.getQueryParameter("title") ?: ""
                val headersParam = uri.getQueryParameter("headers") ?: ""
                val headers = parseHeadersJson(headersParam)
                val silent = uri.getQueryParameter("silent") == "true"
                val app = application as CatCatchApp
                app.pendingDeepLink = DeepLinkData(url, title, headers, silent)
            }
            "http", "https" -> {
                val url = uri.toString()
                if (!url.endsWith(".m3u8", ignoreCase = true) &&
                    !url.contains(".m3u8?", ignoreCase = true)) return
                val app = application as CatCatchApp
                app.pendingDeepLink = DeepLinkData(url, "", emptyMap(), false)
            }
        }
    }

    /**
     * 从分享文本中提取 URL
     * 浏览器分享的内容可能是 "标题 https://example.com/video.m3u8" 或纯 URL
     */
    private fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val urlPattern = Regex("https?://\\S+")
        return urlPattern.find(trimmed)?.value
    }

    /**
     * 解析 headers JSON 格式
     * 支持: {"origin":"...","referer":"..."}
     */
    private fun parseHeadersJson(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()

        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()

        return try {
            val map = mutableMapOf<String, String>()
            val content = trimmed.removeSurrounding("{", "}")
            val pairs = content.split(",")
            for (pair in pairs) {
                val keyValue = pair.split(":", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim().removeSurrounding("\"")
                    val value = keyValue[1].trim().removeSurrounding("\"")
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
