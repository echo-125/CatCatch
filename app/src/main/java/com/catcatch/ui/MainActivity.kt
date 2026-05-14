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
     * 格式: m3u8downloader://add?url=...&title=...&headers=...&referer=...
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return

        // 验证 scheme 和 host
        if (uri.scheme != "m3u8downloader" || uri.host != "add") return

        val url = uri.getQueryParameter("url") ?: return

        // 验证 URL 必须是 HTTP/HTTPS
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        val title = uri.getQueryParameter("title") ?: ""
        val referer = uri.getQueryParameter("referer") ?: ""
        val headersParam = uri.getQueryParameter("headers") ?: ""

        // 组装 headers
        val headers = mutableMapOf<String, String>()
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
        }
        if (headersParam.isNotEmpty()) {
            // headers 格式: "Key1=Value1&Key2=Value2"
            headersParam.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].trim()] = parts[1].trim()
                }
            }
        }

        // 通过 Application 级别 SharedFlow 传递给 HomeViewModel
        val app = application as CatCatchApp
        app.deepLinkFlow.tryEmit(DeepLinkData(url, title, headers))
    }
}
