package com.catcatch.data.remote

import android.util.Log
import com.catcatch.domain.model.M3U8Data
import com.catcatch.domain.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.nio.charset.Charset

/**
 * M3U8 播放列表解析器
 * 支持 Master Playlist 和 Media Playlist 解析，编码自动检测
 * 支持 AES-128-CBC 加密分片（#EXT-X-KEY）
 */
class M3U8Parser(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "M3U8Parser"
        private const val MAX_RECURSION_DEPTH = 5
        private val CHARSET_CANDIDATES = listOf(
            Charsets.UTF_8,
            Charset.forName("big5"),
            Charset.forName("gbk"),
            Charset.forName("gb2312")
        )
    }

    suspend fun parse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        depth: Int = 0
    ): Result<M3U8Data> = withContext(Dispatchers.IO) {
        runCatching {
            if (depth > MAX_RECURSION_DEPTH) {
                throw Exception("M3U8 嵌套层级过深（超过 $MAX_RECURSION_DEPTH 层）")
            }

            val content = fetchM3U8Content(url, headers)

            if (isMasterPlaylist(content)) {
                val streamUrl = selectBestStream(content, url)
                val result = parse(streamUrl, headers, depth + 1)
                result.getOrThrow()
            } else {
                val segments = parseM3U8Content(content, url, headers)
                M3U8Data(url = url, segments = segments)
            }
        }
    }

    private fun fetchM3U8Content(url: String, headers: Map<String, String> = emptyMap()): String {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val bytes = response.body?.bytes() ?: throw Exception("响应内容为空")
        return tryDecodeContent(bytes)
    }

    private fun isMasterPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-STREAM-INF")
    }

    private fun selectBestStream(content: String, baseUrl: String): String {
        val lines = content.lines()
        var bestBandwidth = 0L
        var bestUrl: String? = null

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bandwidth = parseBandwidth(line)
                val nextLine = lines.getOrNull(i + 1)?.trim()
                if (nextLine != null && !nextLine.startsWith("#")) {
                    if (bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        bestUrl = resolveUrl(nextLine, baseUrl)
                    }
                }
            }
        }

        return bestUrl ?: throw Exception("未找到可用的子流")
    }

    private fun parseBandwidth(line: String): Long {
        val bandwidthStr = line.substringAfter("BANDWIDTH=").substringBefore(",").trim()
        return bandwidthStr.toLongOrNull() ?: 0L
    }

    private fun tryDecodeContent(bytes: ByteArray): String {
        for (charset in CHARSET_CANDIDATES) {
            try {
                val content = String(bytes, charset)
                if (content.contains("#EXTM3U")) {
                    return content
                }
            } catch (_: Exception) {
                continue
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 解析 M3U8 内容，提取分片 URL 和加密信息
     */
    private fun parseM3U8Content(
        content: String,
        baseUrl: String,
        headers: Map<String, String> = emptyMap()
    ): List<Segment> {
        val lines = content.lines()
        val segments = mutableListOf<Segment>()
        var segmentIndex = 0
        var currentDuration = 0.0
        var mediaSequence = 0
        var hasMediaSequence = false

        // 当前加密状态（#EXT-X-KEY 可在任意位置出现，影响后续所有分片）
        var currentMethod: String? = null
        var currentKeyUri: String? = null
        var currentIv: ByteArray? = null

        if (!lines.any { it.trim().startsWith("#EXTM3U") }) {
            throw Exception("无效的 M3U8 格式：缺少 #EXTM3U 标记")
        }

        for (line in lines) {
            val trimmedLine = line.trim()

            when {
                trimmedLine.isEmpty() -> continue

                trimmedLine.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = trimmedLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    hasMediaSequence = true
                    segmentIndex = mediaSequence
                }

                trimmedLine.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(trimmedLine.substringAfter(":"))
                    val method = attrs["METHOD"]

                    if (method == "NONE") {
                        currentMethod = null
                        currentKeyUri = null
                        currentIv = null
                        Log.d(TAG, "加密已禁用 (METHOD=NONE)")
                    } else if (method == "AES-128" || method == "SAMPLE-AES") {
                        currentMethod = method
                        currentKeyUri = attrs["URI"]?.let { uri ->
                            // 去掉引号
                            val cleanUri = uri.removeSurrounding("\"")
                            resolveUrl(cleanUri, baseUrl)
                        }
                        currentIv = attrs["IV"]?.let { parseIv(it) }
                        Log.d(TAG, "检测到加密: method=$method, keyUri=$currentKeyUri, iv=${currentIv != null}")
                    }
                }

                trimmedLine.startsWith("#EXTINF:") -> {
                    currentDuration = parseDuration(trimmedLine)
                }

                // 跳过其他 # 标签
                trimmedLine.startsWith("#") -> continue

                // 分片 URL
                else -> {
                    val segmentUrl = resolveUrl(trimmedLine, baseUrl)
                    // 如果没有显式 IV，使用媒体序列号作为 IV（HLS 规范）
                    val iv = currentIv ?: if (currentMethod != null) {
                        intToBigEndianBytes(segmentIndex)
                    } else {
                        null
                    }
                    segments.add(
                        Segment(
                            url = segmentUrl,
                            index = segmentIndex++,
                            duration = currentDuration,
                            encryptionMethod = currentMethod,
                            keyUri = currentKeyUri,
                            iv = iv
                        )
                    )
                    currentDuration = 0.0
                }
            }
        }

        if (segments.isEmpty()) {
            throw Exception("未找到任何分片")
        }

        val encryptedCount = segments.count { it.isEncrypted }
        if (encryptedCount > 0) {
            Log.i(TAG, "解析完成: ${segments.size} 个分片, 其中 $encryptedCount 个加密")
        }

        return segments
    }

    /**
     * 解析 KEY 属性列表，如 METHOD=AES-128,URI="...",IV=0x...
     */
    private fun parseAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < attrString.length) {
            // 跳过空白和逗号
            while (i < attrString.length && (attrString[i] == ' ' || attrString[i] == ',')) i++
            if (i >= attrString.length) break

            // 读取 key
            val keyStart = i
            while (i < attrString.length && attrString[i] != '=') i++
            val key = attrString.substring(keyStart, i).trim()
            if (i >= attrString.length) break
            i++ // 跳过 '='

            // 读取 value（可能被引号包围）
            val value = if (i < attrString.length && attrString[i] == '"') {
                i++ // 跳过开头引号
                val valueStart = i
                while (i < attrString.length && attrString[i] != '"') i++
                val v = attrString.substring(valueStart, i)
                if (i < attrString.length) i++ // 跳过结尾引号
                v
            } else {
                val valueStart = i
                while (i < attrString.length && attrString[i] != ',') i++
                attrString.substring(valueStart, i).trim()
            }

            result[key] = value
        }
        return result
    }

    /**
     * 解析 IV（16 字节十六进制字符串，通常以 0x 开头）
     */
    private fun parseIv(ivStr: String): ByteArray? {
        val hex = ivStr.removeSurrounding("\"").removePrefix("0x").removePrefix("0X")
        if (hex.length != 32) return null // 16 bytes = 32 hex chars
        return try {
            ByteArray(16) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 IV 失败: $ivStr", e)
            null
        }
    }

    /**
     * 将整数转为 16 字节大端序（用于默认 IV）
     */
    private fun intToBigEndianBytes(value: Int): ByteArray {
        val bytes = ByteArray(16)
        bytes[12] = (value shr 24).toByte()
        bytes[13] = (value shr 16).toByte()
        bytes[14] = (value shr 8).toByte()
        bytes[15] = value.toByte()
        return bytes
    }

    private fun parseDuration(line: String): Double {
        val durationStr = line.substringAfter("#EXTINF:").substringBefore(",").trim()
        return durationStr.toDoubleOrNull() ?: 0.0
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        val baseUri = URI(baseUrl)
        val resolvedUri = baseUri.resolve(url)
        return resolvedUri.toString()
    }
}
