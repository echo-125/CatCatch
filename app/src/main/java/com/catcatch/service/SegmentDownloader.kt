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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * 分片下载器
 * 支持单分片下载、进度回调、取消支持、AES-128-CBC 解密
 */
class SegmentDownloader(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "SegmentDownloader"
    }

    // 密钥缓存：keyUri -> 16 字节密钥
    private val keyCache = mutableMapOf<String, ByteArray>()

    suspend fun download(
        segment: Segment,
        outputFile: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (outputFile.exists() && outputFile.length() > 0) {
                onProgress(outputFile.length(), outputFile.length())
                return@runCatching
            }

            val requestBuilder = Request.Builder().url(segment.url)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("下载分片失败: HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("响应内容为空")
            val contentLength = body.contentLength()

            if (segment.isEncrypted && segment.keyUri != null) {
                // 加密分片：下载到内存后解密再写入文件
                downloadAndDecrypt(segment, body.byteStream().readBytes(), outputFile, onProgress, contentLength, headers)
            } else {
                // 非加密分片：直接流式写入
                body.byteStream().use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(8192)
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
        }
    }

    /**
     * 下载加密分片并解密
     */
    private suspend fun downloadAndDecrypt(
        segment: Segment,
        encryptedData: ByteArray,
        outputFile: File,
        onProgress: (Long, Long) -> Unit,
        contentLength: Long,
        headers: Map<String, String> = emptyMap()
    ) {
        val keyUri = segment.keyUri!!
        Log.d(TAG, "下载加密分片 #${segment.index}: ${encryptedData.size} bytes, keyUri=$keyUri")

        onProgress(encryptedData.size.toLong() / 2, contentLength)

        // 获取密钥（带缓存），传递请求头以便访问受保护的密钥 URL
        val key = getOrFetchKey(keyUri, headers)

        onProgress(encryptedData.size.toLong(), contentLength)

        // AES-128-CBC 解密
        val iv = segment.iv ?: throw Exception("加密分片缺少 IV")
        val decrypted = decryptAes128Cbc(encryptedData, key, iv)

        Log.d(TAG, "解密完成: ${encryptedData.size} -> ${decrypted.size} bytes")

        // 去除 PKCS7 填充（可能有 0-16 字节填充）
        val trimmed = removePkcs7Padding(decrypted)

        FileOutputStream(outputFile).use { it.write(trimmed) }
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

    /**
     * AES-128-CBC 解密
     */
    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(data)
    }

    /**
     * 去除 PKCS7 填充
     */
    private fun removePkcs7Padding(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val lastByte = data[data.size - 1].toInt() and 0xFF
        if (lastByte in 1..16) {
            // 验证填充
            val paddingValid = (data.size - lastByte until data.size).all {
                (data[it].toInt() and 0xFF) == lastByte
            }
            if (paddingValid) {
                return data.copyOf(data.size - lastByte)
            }
        }
        return data
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
