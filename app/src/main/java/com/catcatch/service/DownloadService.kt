package com.catcatch.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.catcatch.CatCatchApp
import com.catcatch.R
import com.catcatch.data.repository.DownloadRepository
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
 */
@AndroidEntryPoint
class DownloadService : Service() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_ACTION = "action"
        const val ACTION_CANCEL = "cancel"
        private const val MAX_CONCURRENT_SEGMENTS = 8
        private const val MAX_CONCURRENT_TASKS = 3

        /**
         * 启动下载服务
         */
        fun start(context: Context, taskId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startForegroundService(intent)
        }

        /**
         * 取消下载任务
         */
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<Long, Job>()
    private val taskQueue = LinkedHashSet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1) ?: -1
        if (taskId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action == ACTION_CANCEL) {
            cancelTask(taskId)
            return START_STICKY
        }

        // 启动前台通知
        startForeground(R.string.download_channel_name.hashCode(), createForegroundNotification())

        // 添加到队列并尝试启动
        taskQueue.add(taskId)
        tryStartNext()

        return START_STICKY
    }

    private fun tryStartNext() {
        while (activeTasks.size < MAX_CONCURRENT_TASKS && taskQueue.isNotEmpty()) {
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

        if (activeTasks.isEmpty() && taskQueue.isEmpty()) {
            stopSelf()
        }
    }

    private fun cancelTask(taskId: Long) {
        activeTasks[taskId]?.cancel()
        activeTasks.remove(taskId)
        taskQueue.remove(taskId)
        serviceScope.launch {
            repository.updateTaskStatus(taskId, TaskStatus.CANCELLED)
        }
        tryStartNext()
    }

    private fun updateForegroundNotification() {
        val count = activeTasks.size
        val message = if (count > 0) "正在下载 $count 个任务" else "准备下载..."
        showNotification("猫抓助手", message)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * 下载任务
     */
    private suspend fun downloadTask(taskId: Long) {
        try {
            // 获取任务信息
            val task = repository.getTaskById(taskId) ?: return

            // 更新状态为下载中
            repository.updateTaskStatus(taskId, TaskStatus.DOWNLOADING)

            // 解析 M3U8
            val m3u8Result = repository.parseM3U8(task.url)
            if (m3u8Result.isFailure) {
                repository.updateTaskStatus(
                    taskId,
                    TaskStatus.FAILED,
                    message = "解析 M3U8 失败: ${m3u8Result.exceptionOrNull()?.message}"
                )
                showNotification(task.outputName, "解析失败")
                return
            }

            val m3u8Data = m3u8Result.getOrNull()!!
            val segments = m3u8Data.segments

            // 更新总分片数
            repository.updateTaskStatus(
                taskId,
                TaskStatus.DOWNLOADING,
                total = segments.size
            )

            // 创建下载目录
            val downloadDir = File(task.outputDir)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            // 并发下载分片
            val semaphore = Semaphore(MAX_CONCURRENT_SEGMENTS)
            val completedCount = AtomicInteger(0)
            val segmentProgress = ConcurrentHashMap<Int, Long>()
            var lastUpdateTime = 0L
            val headers = task.headers

            // 第一轮并发下载（带重试）
            coroutineScope {
                segments.map { segment ->
                    async {
                        semaphore.withPermit {
                            val segmentFile = File(downloadDir, "segment_${segment.index}.ts")
                            val result = segmentDownloader.downloadWithRetry(segment, segmentFile, headers) { downloadedBytes, _ ->
                                segmentProgress[segment.index] = downloadedBytes
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime > 500) {
                                    lastUpdateTime = now
                                    val totalDownloaded = segmentProgress.values.sum()
                                    val totalSize = segments.size.toLong() * (segmentProgress.values.maxOrNull() ?: 0L)
                                    val progress = if (totalSize > 0) (totalDownloaded.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
                                    serviceScope.launch {
                                        repository.updateTaskStatus(
                                            taskId,
                                            TaskStatus.DOWNLOADING,
                                            progress = progress,
                                            downloaded = completedCount.get()
                                        )
                                    }
                                }
                            }
                            if (result.isSuccess) {
                                completedCount.incrementAndGet()
                                segmentProgress[segment.index] = segmentFile.length()
                            }
                            result
                        }
                    }
                }.awaitAll()
            }

            // 批量重试：检查失败的分片
            var failedSegments = segments.filter { segment ->
                val file = File(downloadDir, "segment_${segment.index}.ts")
                !file.exists() || file.length() == 0L
            }

            if (failedSegments.isNotEmpty()) {
                // 最多 3 轮批量重试
                repeat(3) { round ->
                    if (failedSegments.isEmpty()) return@repeat
                    delay(2000L * (round + 1))
                    val remaining = mutableListOf<Segment>()
                    for (segment in failedSegments) {
                        val segmentFile = File(downloadDir, "segment_${segment.index}.ts")
                        val result = segmentDownloader.downloadWithRetry(segment, segmentFile, headers)
                        if (result.isSuccess) {
                            completedCount.incrementAndGet()
                        } else {
                            remaining.add(segment)
                        }
                    }
                    failedSegments = remaining
                }

                if (failedSegments.isNotEmpty()) {
                    repository.updateTaskStatus(
                        taskId,
                        TaskStatus.FAILED,
                        message = "${failedSegments.size} 个分片下载失败"
                    )
                    showNotification(task.outputName, "下载失败")
                    return
                }
            }

            val downloaded = completedCount.get()
            repository.updateTaskStatus(
                taskId,
                TaskStatus.DOWNLOADING,
                progress = 1f,
                downloaded = downloaded
            )

            // 合并分片
            repository.updateTaskStatus(taskId, TaskStatus.MERGING)
            showNotification(task.outputName, "合并中...")

            val mergedFile = File(downloadDir, "${task.outputName}.ts")
            mergeSegments(downloadDir, segments.size, mergedFile)

            // 清理临时分片文件
            for (i in 0 until segments.size) {
                File(downloadDir, "segment_$i.ts").delete()
            }

            // FFmpeg 转码
            val outputFile = if (ffmpegConverter.isAvailable()) {
                showNotification(task.outputName, "转码中...")
                val mp4File = File(downloadDir, "${task.outputName}.mp4")
                val convertResult = ffmpegConverter.convertTsToMp4(mergedFile, mp4File)
                if (convertResult.isSuccess) {
                    // 转码成功，删除 TS 文件
                    mergedFile.delete()
                    mp4File
                } else {
                    // 转码失败，保留 TS 文件
                    mergedFile
                }
            } else {
                // FFmpeg 不可用，保留 TS 文件
                mergedFile
            }

            // 更新任务完成
            repository.updateTaskStatus(
                taskId,
                TaskStatus.COMPLETED,
                progress = 1f,
                downloaded = segments.size,
                total = segments.size
            )

            // 显示完成通知
            NotificationUtil.showDownloadComplete(
                this,
                task.outputName,
                outputFile.absolutePath
            )

        } catch (e: Exception) {
            repository.updateTaskStatus(
                taskId,
                TaskStatus.FAILED,
                message = e.message ?: "未知错误"
            )
        }
    }

    /**
     * 合并分片文件
     */
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

    /**
     * 显示前台通知
     */
    private fun showNotification(fileName: String, message: String) {
        val notification = NotificationUtil.createForegroundNotification(
            this,
            fileName,
            message
        )
        startForeground(R.string.download_channel_name.hashCode(), notification)
    }

    /**
     * 创建前台通知
     */
    private fun createForegroundNotification() = NotificationUtil.createForegroundNotification(
        this,
        "猫抓助手",
        "准备下载..."
    )
}
