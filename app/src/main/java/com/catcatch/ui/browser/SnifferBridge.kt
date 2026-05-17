package com.catcatch.ui.browser

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS 与 Kotlin 通信桥接
 * 注入到 WebView 中，供 JS 脚本调用
 */
class SnifferBridge(
    private val onM3u8Found: (url: String, headers: Map<String, String>) -> Unit,
    private val onLog: (message: String) -> Unit,
    private val onTitle: (title: String) -> Unit
) {
    companion object {
        const val BRIDGE_NAME = "Android"
    }

    /**
     * JS 发现 M3U8 链接
     * @param url M3U8 URL
     * @param headersJson JSON 格式的请求头
     */
    @JavascriptInterface
    fun onM3u8Found(url: String, headersJson: String) {
        val headers = parseHeadersJson(headersJson)
        onM3u8Found(url, headers)
    }

    /**
     * JS 日志
     */
    @JavascriptInterface
    fun onLog(message: String) {
        onLog(message)
    }

    /**
     * JS 返回页面标题
     */
    @JavascriptInterface
    fun onTitle(title: String) {
        onTitle(title)
    }

    private fun parseHeadersJson(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val jsonObject = JSONObject(json)
            val map = mutableMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                val value = jsonObject.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) {
                    map[key] = value
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
