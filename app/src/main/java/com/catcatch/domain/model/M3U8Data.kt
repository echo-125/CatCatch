package com.catcatch.domain.model

/**
 * M3U8 解析结果
 */
data class M3U8Data(
    val url: String,
    val segments: List<Segment>,
    val totalDuration: Double = segments.sumOf { it.duration }
)
