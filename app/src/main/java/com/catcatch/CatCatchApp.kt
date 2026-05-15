package com.catcatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.catcatch.data.repository.DownloadRepository
import com.catcatch.util.CacheManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Deep Link 数据
 */
data class DeepLinkData(
    val url: String,
    val title: String,
    val headers: Map<String, String>,
    val silent: Boolean = false
)

/**
 * Application 入口
 */
@HiltAndroidApp
class CatCatchApp : Application() {

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
    }

    @Inject
    lateinit var downloadRepository: DownloadRepository

    // 待处理的 Deep Link 数据（一次性消费）
    @Volatile
    var pendingDeepLink: DeepLinkData? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 启动时自动清理缓存和重置卡住的任务
        appScope.launch {
            // 清理缓存
            val cleared = CacheManager.clearCache(this@CatCatchApp)
            if (cleared > 0) {
                Log.i("CatCatchApp", "启动清理缓存: ${CacheManager.formatSize(cleared)}")
            }

            // 重置卡住的任务（MERGING 或 TRANSCODING 状态）
            // 这些任务可能是 APP 崩溃后残留的
            try {
                downloadRepository.resetStuckTasks()
            } catch (e: Exception) {
                Log.e("CatCatchApp", "重置卡住任务失败: ${e.message}")
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
