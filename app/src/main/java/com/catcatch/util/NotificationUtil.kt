package com.catcatch.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.catcatch.CatCatchApp
import com.catcatch.R
import com.catcatch.ui.MainActivity
import java.io.File

/**
 * 通知工具类
 */
object NotificationUtil {

    private const val COMPLETE_NOTIFICATION_ID = 1002

    /**
     * 显示下载完成通知
     * @param filePath 文件路径，如果以 "/" 开头且文件存在则可直接打开，否则打开应用
     */
    fun showDownloadComplete(
        context: Context,
        fileName: String,
        filePath: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 尝试创建可打开文件的 Intent
        val pendingIntent = try {
            val file = File(filePath)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                // 文件不存在（使用 SAF 的情况），打开应用
                createAppPendingIntent(context)
            }
        } catch (e: Exception) {
            // 出错时打开应用
            createAppPendingIntent(context)
        }

        val notification = NotificationCompat.Builder(context, CatCatchApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("下载完成")
            .setContentText("$fileName - $filePath")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun createAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 创建前台服务通知
     */
    fun createForegroundNotification(
        context: Context,
        title: String,
        message: String
    ): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CatCatchApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
