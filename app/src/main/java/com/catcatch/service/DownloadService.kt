package com.catcatch.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.catcatch.data.repository.DownloadRepository
import com.catcatch.data.repository.SettingsRepository
import com.catcatch.domain.model.Segment
import com.catcatch.domain.model.TaskStatus
import com.catcatch.util.NotificationUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * 下载服务
 * 前台服务，保持后台下载
 * 下载合并完成后立即释放任务槽位，转码作为独立协程后台运行
 */
@AndroidEntryPoint
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_ACTION = "action"
        const val ACTION_CANCEL = "cancel"
        private const val FOREGROUND_NOTIFICATION_ID = 1000

        fun start(context: Context, taskId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context, taskId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_ACTION, ACTION_CANCEL)
            }
            context.startForegroundService(intent)
        }
    }

    @Inject
    lateinit var repository: DownloadRepository

    @Inject
    lateinit var segmentDownloader: SegmentDownloader

    @Inject
    lateinit var ffmpegConverter: FFmpegConverter

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<Long, Job>()
    private val taskQueue = LinkedHashSet<Long>()
    private val transcodeJobs = ConcurrentHashMap<Long, Job>()
    private val schedulerMutex = Mutex()
    // 进度更新锁：确保同一任务的进度更新串行执行，避免协程泄漏
    private val progressMutexes = ConcurrentHashMap<Long, Mutex>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1) ?: -1
        if (taskId == -1L) {
            stopSelfIfIdle()
            return START_NOT_STICKY
        }

        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action == ACTION_CANCEL) {
            startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            cancelTask(taskId)
            return START_STICKY
        }

        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())

        serviceScope.launch {
            schedulerMutex.withLock {
                taskQueue.add(taskId)
            }
            tryStartNext()
        }

        return START_STICKY
    }

    private suspend fun tryStartNext() {
        schedulerMutex.withLock {
            val maxConcurrentTasks = settingsRepository.maxConcurrentTasks.first()
            while (activeTasks.size < maxConcurrentTasks && taskQueue.isNotEmpty()) {
                val taskId = taskQueue.firstOrNull { it !in activeTasks.keys } ?: break
                taskQueue.remove(taskId)

                val job = serviceScope.launch {
                    downloadTask(taskId)
                    activeTasks.remove(taskId)
                    tryStartNext()
                }
                activeTasks[taskId] = job
            }

            updateForegroundNotification()
            stopSelfIfIdle()
        }
    }

    private fun cancelTask(taskId: Long) {
        serviceScope.launch {
            schedulerMutex.withLock {
                activeTasks[taskId]?.cancel()
                activeTasks.remove(taskId)
                transcodeJobs[taskId]?.cancel()
                transcodeJobs.remove(taskId)
                taskQueue.remove(taskId)
                progressMutexes.remove(taskId)
            }
            repository.updateTaskStatus(taskId, TaskStatus.CANCELLED)
            tryStartNext()
        }
    }

    private fun updateForegroundNotification() {
        val downloadCount = activeTasks.size
        val transcodeCount = transcodeJobs.size
        val message = when {
            downloadCount > 0 && transcodeCount > 0 -> "下载 $downloadCount 个, 转码 $transcodeCount 个"
            downloadCount > 0 -> "正在下载 $downloadCount 个任务"
            transcodeCount > 0 -> "正在转码 $transcodeCount 个任务"
            else -> "准备下载..."
        }
        showNotification("猫抓助手", message)
    }

    private fun stopSelfIfIdle() {
        if (activeTasks.isEmpty() && taskQueue.isEmpty() && transcodeJobs.isEmpty()) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * 下载任务：解析 → 下载分片 → 合并 → 释放槽位 → 后台转码
     */
    private suspend fun downloadTask(taskId: Long) {
        val taskTag = "DownloadService"
        try {
            val task = repository.getTaskById(taskId) ?: return
            Log.i(taskTag, "[$taskId] 开始下载任务: ${task.outputName}")

            // 清除旧的视频元信息，避免重试时残留
            repository.updateTaskStatus(
                taskId, TaskStatus.DOWNLOADING,
                duration = 0.0, resolution = "", fileSize = 0
            )

            // 解析 M3U8
            val m3u8Result = repository.parseM3U8(task.url, task.headers)
            if (m3u8Result.isFailure) {
                repository.updateTaskStatus(
                    taskId, TaskStatus.FAILED,
                    message = "解析 M3U8 失败: ${m3u8Result.exceptionOrNull()?.message}"
                )
                showNotification(task.outputName, "解析失败")
                return
            }

            val m3u8Data = m3u8Result.getOrNull()!!
            val segments = m3u8Data.segments
            Log.i(taskTag, "[$taskId] M3U8 解析完成: ${segments.size} 个分片, 总时长=${m3u8Data.totalDuration}s, 加密=${segments.any { it.isEncrypted }}")

            repository.updateTaskStatus(taskId, TaskStatus.DOWNLOADING, total = segments.size, duration = m3u8Data.totalDuration)

            // 获取 SAF URI 和下载目录
            val safUriString = settingsRepository.downloadDirUri.first()
            val useSaf = settingsRepository.useDirPicker.first() && safUriString != null

            val downloadDir = if (useSaf) {
                val transcodeDir = getExternalFilesDir("transcode")
                val tempDir = if (transcodeDir != null) {
                    File(transcodeDir, task.outputName)
                } else {
                    File(cacheDir, "downloads/${task.outputName}")
                }
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir
            } else {
                val dir = File(task.outputDir)
                if (!dir.exists()) dir.mkdirs()
                dir
            }

            // 检查断点续传：统计已存在的分片数
            val existingSegments = segments.indices.count { segmentFile(downloadDir, it).exists() && segmentFile(downloadDir, it).length() > 0 }
            if (existingSegments > 0) {
                Log.i(taskTag, "[$taskId] 断点续传: $existingSegments/${segments.size} 个分片已存在")
            }

            // 并发下载分片
            val maxConcurrentSegments = settingsRepository.maxConcurrentSegments.first()
            val semaphore = Semaphore(maxConcurrentSegments)
            val completedCount = AtomicInteger(0)
            var lastUpdateTime = 0L
            var fileSizeEstimated = 0L
            val headers = task.headers
            var lastLogPercent = -1

            Log.i(taskTag, "[$taskId] 开始并发下载, 并发数=$maxConcurrentSegments")
            val downloadStartTime = System.currentTimeMillis()
            coroutineScope {
                segments.mapIndexed { ordinal, segment ->
                    async {
                        semaphore.withPermit {
                            val segmentFile = segmentFile(downloadDir, ordinal)
                            val result = segmentDownloader.downloadWithRetry(segment, segmentFile, headers)
                            if (result.isSuccess) {
                                val count = completedCount.incrementAndGet()
                                // 每 10% 输出一次里程碑日志
                                val percent = (count * 100 / segments.size)
                                if (percent / 10 > lastLogPercent / 10) {
                                    lastLogPercent = percent
                                    val elapsed = (System.currentTimeMillis() - downloadStartTime) / 1000
                                    Log.i(taskTag, "[$taskId] 下载进度: $count/${segments.size} ($percent%), 已用时=${elapsed}s")
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime > 500) {
                                    lastUpdateTime = now
                                    val progress = count.toFloat() / segments.size
                                    // 估算总文件大小：已下载分片总大小 / 已下载数 * 总数
                                    if (fileSizeEstimated == 0L && count >= 3) {
                                        var downloadedBytes = 0L
                                        for (i in segments.indices) {
                                            val f = segmentFile(downloadDir, i)
                                            if (f.exists()) downloadedBytes += f.length()
                                        }
                                        fileSizeEstimated = downloadedBytes / count * segments.size
                                    }
                                    serviceScope.launch {
                                        // 串行化进度更新，避免协程泄漏
                                        val mutex = progressMutexes.getOrPut(taskId) { Mutex() }
                                        mutex.withLock {
                                            repository.updateTaskStatus(
                                                taskId, TaskStatus.DOWNLOADING,
                                                progress = progress, downloaded = count,
                                                fileSize = fileSizeEstimated
                                            )
                                        }
                                    }
                                }
                            }
                            result
                        }
                    }
                }.awaitAll()
            }
            Log.i(taskTag, "[$taskId] 首轮下载完成: ${completedCount.get()}/${segments.size}, 耗时=${(System.currentTimeMillis() - downloadStartTime) / 1000}s")

            // 批量重试失败分片（支持 401 鉴权过期自动刷新 URL）
            var failedSegments = segments.withIndex().filter { (ordinal, _) ->
                val file = segmentFile(downloadDir, ordinal)
                !file.exists() || file.length() == 0L
            }

            if (failedSegments.isNotEmpty()) {
                Log.w(taskTag, "[$taskId] ${failedSegments.size} 个分片需要重试: ${failedSegments.map { it.index }}")
                var currentSegments = segments

                repeat(3) { round ->
                    if (failedSegments.isEmpty()) return@repeat
                    val waitMs = 2000L * (round + 1)
                    Log.i(taskTag, "[$taskId] 批量重试第 ${round + 1}/3 轮, 等待 ${waitMs}ms...")
                    delay(waitMs)

                    val result = retryFailedSegments(failedSegments, currentSegments, downloadDir, headers, completedCount)

                    // 检查是否有 401 鉴权失败
                    if (result.has401) {
                        Log.i(taskTag, "[$taskId] 检测到 401 鉴权过期，尝试刷新 M3U8 播放列表...")
                        val refreshed = refreshSegmentsFromM3U8(task.url, task.headers)
                        if (refreshed != null && refreshed.size == segments.size) {
                            currentSegments = refreshed
                            Log.i(taskTag, "[$taskId] M3U8 刷新成功，使用新 URL 重试...")
                            // 用新 URL 立即重试一次
                            val retryResult = retryFailedSegments(result.remaining, currentSegments, downloadDir, headers, completedCount)
                            if (retryResult.remaining.isEmpty()) {
                                failedSegments = emptyList()
                                return@repeat
                            }
                            Log.w(taskTag, "[$taskId] 新 URL 重试后仍有 ${retryResult.remaining.size} 个失败")
                            failedSegments = retryResult.remaining
                        } else {
                            Log.e(taskTag, "[$taskId] M3U8 刷新失败")
                            failedSegments = result.remaining
                        }
                    } else {
                        Log.i(taskTag, "[$taskId] 重试第 ${round + 1} 轮完成: 成功=${failedSegments.size - result.remaining.size}, 仍失败=${result.remaining.size}")
                        failedSegments = result.remaining
                    }
                }

                if (failedSegments.isNotEmpty()) {
                    val sampleErrors = failedSegments.take(3).joinToString { "#${it.index}" }
                    val errorMsg = "${failedSegments.size} 个分片下载失败 (例: $sampleErrors)"
                    Log.e(taskTag, "[$taskId] $errorMsg，任务中止")
                    repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = errorMsg)
                    showNotification(task.outputName, "下载失败: ${failedSegments.size} 个分片无法下载")
                    return
                }
            }

            val downloaded = completedCount.get()
            repository.updateTaskStatus(taskId, TaskStatus.DOWNLOADING, progress = 1f, downloaded = downloaded)
            Log.i(taskTag, "[$taskId] 全部分片下载完成: $downloaded/${segments.size}")

            // 合并分片
            repository.updateTaskStatus(taskId, TaskStatus.MERGING)
            showNotification(task.outputName, "合并中...")
            val mergeStart = System.currentTimeMillis()

            val mergedFile = File(downloadDir, "${task.outputName}.ts")
            mergeSegments(downloadDir, segments.size, mergedFile)

            // 清理临时分片文件
            for (i in 0 until segments.size) {
                segmentFile(downloadDir, i).delete()
            }
            Log.i(taskTag, "[$taskId] 合并完成: ${mergedFile.length()} bytes, 耗时=${System.currentTimeMillis() - mergeStart}ms")

            // 合并完成，提取视频信息
            val mergedSize = mergedFile.length()
            val videoInfo = ffmpegConverter.extractVideoInfo(mergedFile)

            // 启动后台转码（不阻塞任务队列）
            val transcodeMode = settingsRepository.transcodeMode.first()
            if (ffmpegConverter.isAvailable(transcodeMode)) {
                val transcodeJob = serviceScope.launch {
                    transcodeAndSave(taskId, task.outputName, mergedFile, downloadDir, useSaf, safUriString, transcodeMode, mergedSize, videoInfo)
                    transcodeJobs.remove(taskId)
                    updateForegroundNotification()
                    stopSelfIfIdle()
                }
                transcodeJobs[taskId] = transcodeJob
                updateForegroundNotification()
            } else {
                // 无转码，直接复制到 SAF
                val savedPath = if (useSaf) {
                    copyAndCleanup(mergedFile, task.outputName, safUriString!!, downloadDir)
                } else {
                    mergedFile.absolutePath
                }
                repository.updateTaskStatus(
                    taskId, TaskStatus.COMPLETED,
                    progress = 1f, downloaded = segments.size, total = segments.size,
                    resolution = videoInfo.resolution, fileSize = mergedSize,
                    savedPath = savedPath
                )
                NotificationUtil.showDownloadComplete(this, task.outputName, savedPath)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = e.message ?: "未知错误")
        }
    }

    /**
     * 后台转码 + SAF 复制（独立协程，不影响任务队列）
     */
    private suspend fun transcodeAndSave(
        taskId: Long,
        outputName: String,
        mergedFile: File,
        downloadDir: File,
        useSaf: Boolean,
        safUriString: String?,
        transcodeMode: Int = 0,
        mergedSize: Long = 0,
        videoInfo: FFmpegConverter.VideoInfo = FFmpegConverter.VideoInfo()
    ) {
        try {
            repository.updateTaskStatus(taskId, TaskStatus.TRANSCODING, message = "转码中...")
            showNotification(outputName, "转码中...")

            val mp4File = File(downloadDir, "$outputName.mp4")
            val externalDir = getExternalFilesDir("transcode")
            val convertResult = ffmpegConverter.convertTsToMp4(mergedFile, mp4File, externalDir, transcodeMode)

            if (convertResult.isSuccess) {
                // 在 SAF 复制前先提取信息（copyAndCleanup 会删除源文件）
                val mp4VideoInfo = ffmpegConverter.extractVideoInfo(mp4File)
                val mp4Size = mp4File.length()
                val finalResolution = mp4VideoInfo.resolution.ifEmpty { videoInfo.resolution }
                mergedFile.delete()
                val savedPath = if (useSaf) {
                    copyAndCleanup(mp4File, outputName, safUriString!!, downloadDir)
                } else {
                    mp4File.absolutePath
                }
                repository.updateTaskStatus(
                    taskId, TaskStatus.COMPLETED,
                    progress = 1f, message = "转码完成",
                    resolution = finalResolution, fileSize = mp4Size,
                    savedPath = savedPath
                )
                NotificationUtil.showDownloadComplete(this, outputName, savedPath)
            } else {
                val error = convertResult.exceptionOrNull()?.message ?: "转码未知错误"
                Log.e(TAG, "[$taskId] 转码失败: $error")
                // 转码失败，保留 TS 文件，复制到 SAF
                if (useSaf) {
                    val savedPath = copyAndCleanup(mergedFile, outputName, safUriString!!, downloadDir)
                    repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = "转码失败: $error",
                        resolution = videoInfo.resolution, fileSize = mergedSize,
                        savedPath = savedPath)
                    NotificationUtil.showDownloadComplete(this, outputName, savedPath)
                } else {
                    repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = "转码失败: $error",
                        resolution = videoInfo.resolution, fileSize = mergedSize,
                        savedPath = mergedFile.absolutePath)
                    NotificationUtil.showDownloadComplete(this, outputName, mergedFile.absolutePath)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[$taskId] 转码异常", e)
            repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = "转码异常: ${e.message}")
        }
    }

    /**
     * 复制文件到 SAF 目录并清理临时目录
     * 失败时抛出异常，不静默回退
     */
    private fun copyAndCleanup(sourceFile: File, outputName: String, safUriString: String, downloadDir: File): String {
        val safUri = Uri.parse(safUriString)
        val result = copyFileToSaf(sourceFile, outputName, safUri)
        sourceFile.delete()
        downloadDir.deleteRecursively()
        return result.displayName
    }

    private fun mergeSegments(downloadDir: File, segmentCount: Int, outputFile: File) {
        outputFile.outputStream().use { output ->
            for (i in 0 until segmentCount) {
                val segmentFile = segmentFile(downloadDir, i)
                if (segmentFile.exists()) {
                    segmentFile.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
            }
        }
    }

    private fun segmentFile(downloadDir: File, ordinal: Int): File {
        return File(downloadDir, "segment_$ordinal.ts")
    }

    /**
     * 重试失败分片的结果
     */
    private data class RetryResult(
        val remaining: List<IndexedValue<Segment>>,
        val has401: Boolean
    )

    /**
     * 重试一批失败分片，返回剩余失败列表和是否有 401 错误
     */
    private suspend fun retryFailedSegments(
        failed: List<IndexedValue<Segment>>,
        segments: List<Segment>,
        downloadDir: File,
        headers: Map<String, String>,
        completedCount: AtomicInteger
    ): RetryResult {
        var has401 = false
        val remaining = mutableListOf<IndexedValue<Segment>>()
        for ((ordinal, _) in failed) {
            val segment = segments[ordinal]
            val file = segmentFile(downloadDir, ordinal)
            val result = segmentDownloader.downloadWithRetry(segment, file, headers)
            if (result.isSuccess) {
                completedCount.incrementAndGet()
            } else {
                remaining.add(IndexedValue(ordinal, segment))
                val ex = result.exceptionOrNull()
                if (ex is DownloadException && ex.httpCode == 401) {
                    has401 = true
                }
            }
        }
        return RetryResult(remaining, has401)
    }

    /**
     * 重新解析 M3U8 获取新的分片 URL（含新鉴权 token）
     * 返回 null 表示刷新失败
     */
    private suspend fun refreshSegmentsFromM3U8(
        url: String,
        headers: Map<String, String>
    ): List<Segment>? {
        return try {
            val result = repository.parseM3U8(url, headers)
            result.getOrNull()?.segments
        } catch (e: Exception) {
            Log.e(TAG, "刷新 M3U8 失败: ${e.message}")
            null
        }
    }

    private data class SafCopyResult(val displayName: String, val uri: Uri)

    private fun copyFileToSaf(sourceFile: File, outputName: String, safUri: Uri): SafCopyResult {
        val dir = DocumentFile.fromTreeUri(this, safUri) ?: throw Exception("SAF 目录不可用")

        val extension = sourceFile.extension
        val fileNameWithExt = "$outputName.$extension"
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

        val targetFile = dir.createFile(mimeType, fileNameWithExt) ?: throw Exception("无法创建 SAF 文件")

        contentResolver.openOutputStream(targetFile.uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output, bufferSize = 256 * 1024)
            }
        } ?: throw Exception("无法打开 SAF 输出流")

        // 返回实际保存的文件名（带后缀）
        return SafCopyResult(fileNameWithExt, targetFile.uri)
    }

    private fun showNotification(fileName: String, message: String) {
        val notification = NotificationUtil.createForegroundNotification(this, fileName, message)
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun createForegroundNotification() = NotificationUtil.createForegroundNotification(
        this, "猫抓助手", "准备下载..."
    )
}
