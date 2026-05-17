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

    val metaText: String
        get() = listOfNotNull(
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
    DEEP_SCAN("深度扫描")
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
