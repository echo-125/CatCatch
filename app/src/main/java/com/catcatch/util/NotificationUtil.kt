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
     */
    fun showDownloadComplete(
        context: Context,
        fileName: String,
        filePath: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val file = File(filePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CatCatchApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("下载完成")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
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
