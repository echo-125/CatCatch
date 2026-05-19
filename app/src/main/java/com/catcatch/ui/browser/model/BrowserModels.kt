package com.catcatch.ui.browser.model

/**
 * 嗅探到的 M3U8 链接
 */
data class SniffedLink(
    val url: String,
    val fileName: String = "",
    val headers: Map<String, String> = emptyMap(),
    val duration: Double = 0.0,
    val variants: List<Variant> = emptyList(),
    val isPlaylist: Boolean = false,
    val source: SniffSource = SniffSource.NETWORK,
    val timestamp: Long = System.currentTimeMillis(),
    val selectedVariantIndex: Int = 0
) {
    /** 获取选中变体的 URL，无变体时返回自身 URL */
    val selectedUrl: String
        get() = if (variants.isNotEmpty()) {
            variants.getOrElse(selectedVariantIndex) { variants.first() }.url
        } else url

    /** 最高码率变体 */
    val bestVariant: Variant?
        get() = variants.maxByOrNull { it.bandwidth }

    /** 是否有多个变体可选 */
    val hasVariants: Boolean
        get() = variants.size > 1

    // 新增: 估算文件大小（字节），基于选中变体的 bandwidth * duration
    val estimatedSize: Long
        get() {
            if (duration <= 0) return 0
            val selectedBandwidth = if (variants.isNotEmpty()) {
                variants.getOrElse(selectedVariantIndex) { variants.first() }.bandwidth
            } else {
                bestVariant?.bandwidth ?: 0
            }
            if (selectedBandwidth <= 0) return 0
            // bandwidth (bits/s) / 8 = bytes/s, * duration(s) = total bytes
            return ((selectedBandwidth / 8.0) * duration).toLong()
        }

    // 新增: 格式化的文件大小文本
    val estimatedSizeText: String
        get() {
            val bytes = estimatedSize
            if (bytes <= 0) {
                // 如果没有 bandwidth 信息，但有 duration，显示时长
                if (duration > 0) {
                    return "时长 ${formatDuration(duration)}"
                }
                return "未知"
            }
            return when {
                bytes < 1024 -> "${bytes}B"
                bytes < 1024 * 1024 -> "${bytes / 1024}KB"
                bytes < 1024 * 1024 * 1024 -> String.format("约 %.1fMB", bytes / (1024.0 * 1024.0))
                else -> String.format("约 %.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }

    // 新增: 选中变体的编解码器显示文本
    val selectedCodecDisplay: String
        get() {
            if (variants.isNotEmpty()) {
                return variants.getOrElse(selectedVariantIndex) { variants.first() }.codecDisplay
            }
            // 单码率 M3U8 通常没有 codec 信息
            return ""
        }
}

/**
 * 多分辨率变体
 */
data class Variant(
    val url: String,
    val bandwidth: Long = 0,
    val resolution: String? = null,
    val codecs: String? = null,
    val frameRate: Double? = null
) {
    val bandwidthText: String
        get() = when {
            bandwidth >= 1_000_000 -> "${"%.1f".format(bandwidth / 1_000_000.0)} Mbps"
            bandwidth >= 1_000 -> "${bandwidth / 1_000} Kbps"
            else -> "$bandwidth bps"
        }

    val resolutionText: String
        get() = resolution ?: "未知"

    // 新增: 视频编解码器友好名称
    val videoCodecName: String
        get() {
            val c = codecs ?: return ""
            return when {
                c.contains("avc1") || c.contains("avc3") -> "H.264"
                c.contains("hev1") || c.contains("hvc1") -> "H.265"
                c.contains("vp09") || c.contains("vp9")  -> "VP9"
                c.contains("av01")                        -> "AV1"
                else -> c.split(",").firstOrNull()?.take(8) ?: ""
            }
        }

    // 新增: 音频编解码器友好名称
    val audioCodecName: String
        get() {
            val c = codecs ?: return ""
            return when {
                c.contains("mp4a") -> "AAC"
                c.contains("ac-3") -> "AC3"
                c.contains("ec-3") -> "E-AC3"
                c.contains("opus") -> "Opus"
                else -> ""
            }
        }

    // 新增: 编解码器显示文本 (如 "H.264 + AAC")
    val codecDisplay: String
        get() = listOfNotNull(
            videoCodecName.ifEmpty { null },
            audioCodecName.ifEmpty { null }
        ).joinToString(" + ").ifEmpty { "未知" }

    val metaText: String
        get() = listOfNotNull(
            videoCodecName.ifEmpty { null },
            bandwidthText,
            frameRate?.let { "%.1f fps".format(it) }
        ).joinToString(" · ")
}

/**
 * 嗅探来源
 */
enum class SniffSource(val label: String) {
    NETWORK("网络拦截"),
    DOM("DOM 监听"),
    DEEP_SCAN("深度扫描"),
    PLAYER("播放器识别")
}

/**
 * 网站配置
 */
data class SiteConfig(
    val domain: String,
    val name: String,
    val useIndexFilter: Boolean = false,
    val autoPlay: Boolean = false,
    val blockAds: Boolean = false
)

/**
 * 格式化时长
 */
fun formatDuration(seconds: Double): String {
    if (seconds < 0) return "未知"
    val h = (seconds / 3600).toInt()
    val m = ((seconds % 3600) / 60).toInt()
    val s = (seconds % 60).toInt()
    return when {
        h > 0 -> "${h}小时${m}分${s}秒"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}
