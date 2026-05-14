package com.catcatch.domain.model

/**
 * M3U8 分片信息
 */
data class Segment(
    val url: String,
    val index: Int,
    val duration: Double = 0.0
)
