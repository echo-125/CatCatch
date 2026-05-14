package com.catcatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Deep Link 数据
 */
data class DeepLinkData(
    val url: String,
    val title: String,
    val headers: Map<String, String>
)

/**
 * Application 入口
 */
@HiltAndroidApp
class CatCatchApp : Application() {

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
    }

    // Deep Link 数据流，replay=1 确保 HomeViewModel 启动后能收到
    val deepLinkFlow = MutableSharedFlow<DeepLinkData>(replay = 1)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
