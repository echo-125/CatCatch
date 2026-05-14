package com.catcatch.service

import com.catcatch.domain.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 分片下载器
 * 支持单分片下载、进度回调、取消支持
 */
class SegmentDownloader(private val client: OkHttpClient) {

    /**
     * 下载单个分片到文件
     *
     * @param segment 分片信息
     * @param outputFile 输出文件
     * @param headers 自定义请求头
     * @param onProgress 进度回调 (已下载字节数, 总字节数)
     */
    suspend fun download(
        segment: Segment,
        outputFile: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 断点续传：跳过已存在且非空的文件
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

            body.byteStream().use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        // 检查协程是否已取消
                        coroutineContext.ensureActive()

                        outputStream.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, contentLength)
                    }
                }
            }
        }
    }

    /**
     * 带重试的分片下载
     *
     * @param segment 分片信息
     * @param outputFile 输出文件
     * @param headers 自定义请求头
     * @param maxRetries 最大重试次数
     * @param onProgress 进度回调
     */
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
            // 删除部分文件
            if (outputFile.exists()) outputFile.delete()
            // 指数退避：1s, 2s, 4s
            if (attempt < maxRetries - 1) {
                delay(1000L * (1 shl attempt))
            }
        }
        return Result.failure(lastException ?: Exception("下载失败"))
    }
}
