package com.catcatch.ui.browser

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS 与 Kotlin 通信桥接
 * 注入到 WebView 中，供 JS 脚本调用
 */
class SnifferBridge(
    private val m3u8FoundCallback: (url: String, headers: Map<String, String>) -> Unit,
    private val logCallback: (message: String) -> Unit,
    private val titleCallback: (title: String) -> Unit
) {
    companion object {
        const val BRIDGE_NAME = "Android"
    }

    @JavascriptInterface
    fun onM3u8Found(url: String, headersJson: String) {
        val headers = parseHeadersJson(headersJson)
        m3u8FoundCallback(url, headers)
    }

    @JavascriptInterface
    fun onLog(message: String) {
        logCallback(message)
    }

    @JavascriptInterface
    fun onTitle(title: String) {
        titleCallback(title)
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
