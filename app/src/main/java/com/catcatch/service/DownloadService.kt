package com.catcatch.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1) ?: -1
        if (taskId == -1L) {
            stopSelfIfIdle()
            return START_NOT_STICKY
        }

        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action == ACTION_CANCEL) {
            cancelTask(taskId)
            return START_STICKY
        }

        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())

        taskQueue.add(taskId)
        serviceScope.launch { tryStartNext() }

        return START_STICKY
    }

    private suspend fun tryStartNext() {
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

    private fun cancelTask(taskId: Long) {
        activeTasks[taskId]?.cancel()
        activeTasks.remove(taskId)
        taskQueue.remove(taskId)
        transcodeJobs[taskId]?.cancel()
        transcodeJobs.remove(taskId)
        serviceScope.launch {
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
        try {
            val task = repository.getTaskById(taskId) ?: return

            android.util.Log.d("DownloadService", "开始下载任务: $taskId, 输出目录: ${task.outputDir}, 输出文件名: ${task.outputName}")

            repository.updateTaskStatus(taskId, TaskStatus.DOWNLOADING)

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

            repository.updateTaskStatus(taskId, TaskStatus.DOWNLOADING, total = segments.size)

            // 获取 SAF URI 和下载目录
            val safUriString = settingsRepository.downloadDirUri.first()
            val useSaf = safUriString != null

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

            // 并发下载分片
            val maxConcurrentSegments = settingsRepository.maxConcurrentSegments.first()
            val semaphore = Semaphore(maxConcurrentSegments)
            val completedCount = AtomicInteger(0)
            var lastUpdateTime = 0L
            val headers = task.headers

            android.util.Log.d("DownloadService", "开始下载分片，并发数: $maxConcurrentSegments")
            coroutineScope {
                segments.map { segment ->
                    async {
                        semaphore.withPermit {
                            val segmentFile = File(downloadDir, "segment_${segment.index}.ts")
                            val result = segmentDownloader.downloadWithRetry(segment, segmentFile, headers)
                            if (result.isSuccess) {
                                val count = completedCount.incrementAndGet()
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime > 500) {
                                    lastUpdateTime = now
                                    val progress = count.toFloat() / segments.size
                                    serviceScope.launch {
                                        repository.updateTaskStatus(
                                            taskId, TaskStatus.DOWNLOADING,
                                            progress = progress, downloaded = count
                                        )
                                    }
                                }
                            }
                            result
                        }
                    }
                }.awaitAll()
            }

            // 批量重试失败分片
            var failedSegments = segments.filter { segment ->
                val file = File(downloadDir, "segment_${segment.index}.ts")
                !file.exists() || file.length() == 0L
            }

            if (failedSegments.isNotEmpty()) {
                repeat(3) { round ->
                    if (failedSegments.isEmpty()) return@repeat
                    delay(2000L * (round + 1))
                    val remaining = mutableListOf<Segment>()
                    for (segment in failedSegments) {
                        val segmentFile = File(downloadDir, "segment_${segment.index}.ts")
                        val result = segmentDownloader.downloadWithRetry(segment, segmentFile, headers)
                        if (result.isSuccess) completedCount.incrementAndGet() else remaining.add(segment)
                    }
                    failedSegments = remaining
                }

                if (failedSegments.isNotEmpty()) {
                    repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = "${failedSegments.size} 个分片下载失败")
                    showNotification(task.outputName, "下载失败")
                    return
                }
            }

            val downloaded = completedCount.get()
            repository.updateTaskStatus(taskId, TaskStatus.DOWNLOADING, progress = 1f, downloaded = downloaded)

            // 合并分片
            repository.updateTaskStatus(taskId, TaskStatus.MERGING)
            showNotification(task.outputName, "合并中...")

            val mergedFile = File(downloadDir, "${task.outputName}.ts")
            mergeSegments(downloadDir, segments.size, mergedFile)

            // 清理临时分片文件
            for (i in 0 until segments.size) {
                File(downloadDir, "segment_$i.ts").delete()
            }

            // 合并完成，标记任务完成并释放槽位
            repository.updateTaskStatus(
                taskId, TaskStatus.COMPLETED,
                progress = 1f, downloaded = segments.size, total = segments.size
            )

            // 启动后台转码（不阻塞任务队列）
            if (ffmpegConverter.isAvailable()) {
                val transcodeJob = serviceScope.launch {
                    transcodeAndSave(taskId, task.outputName, mergedFile, downloadDir, useSaf, safUriString)
                    transcodeJobs.remove(taskId)
                    updateForegroundNotification()
                    stopSelfIfIdle()
                }
                transcodeJobs[taskId] = transcodeJob
                updateForegroundNotification()
            } else {
                // 无转码，直接复制到 SAF
                if (useSaf) {
                    copyAndCleanup(mergedFile, task.outputName, safUriString!!, downloadDir)
                }
                NotificationUtil.showDownloadComplete(this, task.outputName, mergedFile.absolutePath)
            }

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
        safUriString: String?
    ) {
        try {
            repository.updateTaskStatus(taskId, TaskStatus.TRANSCODING, message = "转码中...")
            showNotification(outputName, "转码中...")

            val mp4File = File(downloadDir, "$outputName.mp4")
            val externalDir = getExternalFilesDir("transcode")
            val convertResult = ffmpegConverter.convertTsToMp4(mergedFile, mp4File, externalDir)

            if (convertResult.isSuccess) {
                mergedFile.delete()
                val savedPath = if (useSaf) {
                    copyAndCleanup(mp4File, outputName, safUriString!!, downloadDir)
                } else {
                    mp4File.absolutePath
                }
                NotificationUtil.showDownloadComplete(this, outputName, savedPath)
            } else {
                val error = convertResult.exceptionOrNull()?.message ?: "转码未知错误"
                android.util.Log.e("DownloadService", "转码失败: $error")
                // 转码失败，保留 TS 文件
                if (useSaf) {
                    copyAndCleanup(mergedFile, outputName, safUriString!!, downloadDir)
                }
                repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = "转码失败: $error")
                NotificationUtil.showDownloadComplete(this, outputName, mergedFile.absolutePath)
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "转码异常", e)
            repository.updateTaskStatus(taskId, TaskStatus.FAILED, message = "转码异常: ${e.message}")
        }
    }

    /**
     * 复制文件到 SAF 目录并清理临时目录
     */
    private fun copyAndCleanup(sourceFile: File, outputName: String, safUriString: String, downloadDir: File): String {
        return try {
            val safUri = Uri.parse(safUriString)
            val result = copyFileToSaf(sourceFile, outputName, safUri)
            sourceFile.delete()
            downloadDir.deleteRecursively()
            result
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "复制到 SAF 目录失败", e)
            sourceFile.absolutePath
        }
    }

    private fun mergeSegments(downloadDir: File, segmentCount: Int, outputFile: File) {
        outputFile.outputStream().use { output ->
            for (i in 0 until segmentCount) {
                val segmentFile = File(downloadDir, "segment_$i.ts")
                if (segmentFile.exists()) {
                    segmentFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyFileToSaf(sourceFile: File, outputName: String, safUri: Uri): String {
        val dir = DocumentFile.fromTreeUri(this, safUri) ?: return "SAF 目录"

        val extension = sourceFile.extension
        val fileNameWithExt = "$outputName.$extension"
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

        val targetFile = dir.createFile(mimeType, fileNameWithExt) ?: return "SAF 目录"

        try {
            contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "复制文件失败", e)
        }

        return "已保存到选择的目录"
    }

    private fun showNotification(fileName: String, message: String) {
        val notification = NotificationUtil.createForegroundNotification(this, fileName, message)
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun createForegroundNotification() = NotificationUtil.createForegroundNotification(
        this, "猫抓助手", "准备下载..."
    )
}
