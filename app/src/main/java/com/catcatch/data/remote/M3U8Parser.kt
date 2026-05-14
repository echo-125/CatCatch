package com.catcatch.data.remote

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
 */
class M3U8Parser(private val client: OkHttpClient) {

    companion object {
        private const val MAX_RECURSION_DEPTH = 5
        private val CHARSET_CANDIDATES = listOf(
            Charsets.UTF_8,
            Charset.forName("big5"),
            Charset.forName("gbk"),
            Charset.forName("gb2312")
        )
    }

    /**
     * 解析 M3U8 URL，返回分片列表
     * 支持递归解析 Master Playlist，自动选择最高码率子流
     */
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

            // 检测是否为 Master Playlist
            if (isMasterPlaylist(content)) {
                val streamUrl = selectBestStream(content, url)
                // 递归解析选中的子流
                val result = parse(streamUrl, headers, depth + 1)
                result.getOrThrow()
            } else {
                val segments = parseM3U8Content(content, url)
                M3U8Data(url = url, segments = segments)
            }
        }
    }

    /**
     * 获取 M3U8 文件内容
     */
    private fun fetchM3U8Content(url: String, headers: Map<String, String> = emptyMap()): String {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val bytes = response.body?.bytes() ?: throw Exception("响应内容为空")

        // 尝试不同编码解码
        return tryDecodeContent(bytes)
    }

    /**
     * 检测是否为 Master Playlist
     * Master Playlist 包含 #EXT-X-STREAM-INF 标签
     */
    private fun isMasterPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-STREAM-INF")
    }

    /**
     * 从 Master Playlist 中选择最高码率的子流
     */
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

    /**
     * 解析带宽值
     */
    private fun parseBandwidth(line: String): Long {
        val bandwidthStr = line.substringAfter("BANDWIDTH=").substringBefore(",").trim()
        return bandwidthStr.toLongOrNull() ?: 0L
    }

    /**
     * 尝试不同编码解码内容
     */
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

        // 如果所有编码都失败，使用 UTF-8 并忽略错误
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 解析 M3U8 内容，提取分片 URL
     */
    private fun parseM3U8Content(content: String, baseUrl: String): List<Segment> {
        val lines = content.lines()
        val segments = mutableListOf<Segment>()
        var segmentIndex = 0
        var currentDuration = 0.0

        // 验证 M3U8 格式
        if (!lines.any { it.trim().startsWith("#EXTM3U") }) {
            throw Exception("无效的 M3U8 格式：缺少 #EXTM3U 标记")
        }

        for (line in lines) {
            val trimmedLine = line.trim()

            when {
                // 跳过注释和空行
                trimmedLine.isEmpty() || trimmedLine.startsWith("#") -> {
                    // 解析分片时长
                    if (trimmedLine.startsWith("#EXTINF:")) {
                        currentDuration = parseDuration(trimmedLine)
                    }
                }

                // 分片 URL
                else -> {
                    val segmentUrl = resolveUrl(trimmedLine, baseUrl)
                    segments.add(
                        Segment(
                            url = segmentUrl,
                            index = segmentIndex++,
                            duration = currentDuration
                        )
                    )
                    currentDuration = 0.0
                }
            }
        }

        if (segments.isEmpty()) {
            throw Exception("未找到任何分片")
        }

        return segments
    }

    /**
     * 解析分片时长
     */
    private fun parseDuration(line: String): Double {
        val durationStr = line.substringAfter("#EXTINF:").substringBefore(",").trim()
        return durationStr.toDoubleOrNull() ?: 0.0
    }

    /**
     * 将相对 URL 解析为绝对 URL
     */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }

        val baseUri = URI(baseUrl)
        val resolvedUri = baseUri.resolve(url)
        return resolvedUri.toString()
    }
}
