package com.catcatch.domain.model

/**
 * M3U8 分片信息
 */
data class Segment(
    val url: String,
    val index: Int,
    val duration: Double = 0.0,
    val encryptionMethod: String? = null,
    val keyUri: String? = null,
    val iv: ByteArray? = null
) {
    val isEncrypted: Boolean get() = encryptionMethod != null && encryptionMethod != "NONE"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Segment) return false
        return url == other.url && index == other.index
    }

    override fun hashCode(): Int = 31 * url.hashCode() + index
}
