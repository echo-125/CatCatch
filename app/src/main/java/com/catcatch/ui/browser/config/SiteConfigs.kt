package com.catcatch.ui.browser.config

import com.catcatch.ui.browser.model.SiteConfig

/**
 * 预置网站配置
 * 从油猴脚本 SITE_CONFIGS 提取
 */
object SiteConfigs {
    private val configs = listOf(
        SiteConfig(
            domain = "chigua.com",
            name = "吃瓜",
            useIndexFilter = false,
            autoPlay = false
        ),
        SiteConfig(
            domain = "51cg1.com",
            name = "吃瓜",
            useIndexFilter = false,
            autoPlay = false
        ),
        SiteConfig(
            domain = "yggihubp.com",
            name = "吃瓜",
            useIndexFilter = false,
            autoPlay = false
        ),
        SiteConfig(
            domain = "rouva",
            name = "Rou",
            useIndexFilter = true,
            autoPlay = true,
            blockAds = true
        ),
        SiteConfig(
            domain = "missav.live",
            name = "MissAV",
            useIndexFilter = true,
            autoPlay = false
        )
    )

    /**
     * 根据 URL 获取网站配置
     */
    fun getConfig(url: String): SiteConfig? {
        val hostname = try {
            java.net.URI(url).host ?: return null
        } catch (e: Exception) {
            return null
        }

        return configs.find { config ->
            hostname.contains(config.domain, ignoreCase = true)
        }
    }

    /**
     * 广告关键词列表
     */
    val AD_KEYWORDS = listOf(
        "ad", "ads", "adv", "advertisement", "silent-basis",
        "banner", "popup", "sponsor"
    )

    /**
     * 检查 URL 是否是广告
     */
    fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return AD_KEYWORDS.any { keyword ->
            lowerUrl.contains(keyword)
        }
    }
}
