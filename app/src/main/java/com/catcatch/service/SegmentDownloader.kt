package com.catcatch.service

import android.util.Log
import com.catcatch.domain.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * 分片下载器
 * 支持单分片下载、进度回调、取消支持、AES-128-CBC 流式解密
 */
class SegmentDownloader(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "SegmentDownloader"
        private const val BUFFER_SIZE = 8192
    }

    // 密钥缓存：keyUri -> 16 字节密钥（多协程并发访问，使用 ConcurrentHashMap）
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    suspend fun download(
        segment: Segment,
        outputFile: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 快速路径：非空文件直接视为已完成（避免对每个已完成分片发 HTTP 请求导致 CDN 限流）
            if (outputFile.exists() && outputFile.length() > 0) {
                onProgress(outputFile.length(), outputFile.length())
                return@runCatching
            }

            val requestBuilder = Request.Builder().url(segment.url)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    throw Exception("下载分片失败: HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("响应内容为空")
                val contentLength = body.contentLength()

                if (segment.isEncrypted && segment.keyUri != null) {
                    downloadAndDecryptStream(segment, body.byteStream(), outputFile, onProgress, contentLength, headers)
                } else {
                    body.byteStream().use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var downloaded = 0L
                            var bytesRead: Int

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                coroutineContext.ensureActive()
                                outputStream.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                onProgress(downloaded, contentLength)
                            }
                        }
                    }
                }

                // 下载完成后验证：如果服务端报告了大小但不匹配，删除文件以便重试
                val expectedSize = response.body?.contentLength() ?: -1
                if (expectedSize > 0 && outputFile.length() != expectedSize) {
                    outputFile.delete()
                    throw Exception("分片大小不匹配: 期望 $expectedSize, 实际 ${outputFile.length()}")
                }
            } finally {
                response.close()
            }
        }
    }

    /**
     * 流式下载加密分片并解密
     * 使用 CipherInputStream 边下载边解密，避免整个分片加载到内存
     */
    private suspend fun downloadAndDecryptStream(
        segment: Segment,
        encryptedStream: java.io.InputStream,
        outputFile: File,
        onProgress: (Long, Long) -> Unit,
        contentLength: Long,
        headers: Map<String, String> = emptyMap()
    ) {
        val keyUri = segment.keyUri!!
        Log.d(TAG, "下载加密分片 #${segment.index}（流式解密）: keyUri=$keyUri")

        // 获取密钥（带缓存）
        val key = getOrFetchKey(keyUri, headers)

        // AES-128-CBC 解密，PKCS5Padding 自动处理填充
        val iv = segment.iv ?: throw Exception("加密分片缺少 IV")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        // CipherInputStream 流式解密：边读取边解密边写入，内存占用恒定
        CipherInputStream(encryptedStream, cipher).use { decryptStream ->
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = 0L
                var bytesRead: Int

                while (decryptStream.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()
                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(downloaded, contentLength)
                }
            }
        }

        Log.d(TAG, "加密分片 #${segment.index} 流式解密完成")
    }

    /**
     * 获取密钥（带缓存）
     */
    private suspend fun getOrFetchKey(keyUri: String, headers: Map<String, String>): ByteArray {
        keyCache[keyUri]?.let { return it }

        Log.d(TAG, "下载密钥: $keyUri")
        val requestBuilder = Request.Builder().url(keyUri)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw Exception("下载密钥失败: HTTP ${response.code}")
        }
        val keyBytes = response.body?.bytes() ?: throw Exception("密钥内容为空")
        if (keyBytes.size != 16) {
            throw Exception("密钥长度异常: ${keyBytes.size} bytes (期望 16)")
        }

        keyCache[keyUri] = keyBytes
        Log.d(TAG, "密钥已缓存: ${keyBytes.size} bytes")
        return keyBytes
    }

    suspend fun downloadWithRetry(
        segment: Segment,
        outputFile: File,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 3,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            val result = download(segment, outputFile, headers, onProgress)
            if (result.isSuccess) return Result.success(Unit)
            lastException = result.exceptionOrNull() as? Exception
            if (outputFile.exists()) outputFile.delete()
            if (attempt < maxRetries - 1) {
                delay(1000L * (1 shl attempt))
            }
        }
        return Result.failure(lastException ?: Exception("下载失败"))
    }
}
