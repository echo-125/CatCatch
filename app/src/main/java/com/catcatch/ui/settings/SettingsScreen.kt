package com.catcatch.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.catcatch.ui.components.CatCatchTopAppBar

/**
 * 设置页面
 * 使用 LazyColumn 和分组标题
 */
@Composable
fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        CatCatchTopAppBar(title = "设置")

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // 下载设置
            item {
                SettingsSection(title = "下载设置") {
                    SettingsItem(
                        icon = Icons.Default.Folder,
                        title = "下载目录",
                        subtitle = "/storage/emulated/0/Download/CatCatch",
                        onClick = { /* TODO: 选择目录 */ }
                    )
                    SettingsItem(
                        icon = Icons.Default.Speed,
                        title = "最大并发任务数",
                        subtitle = "3",
                        onClick = { /* TODO: 设置并发数 */ }
                    )
                    SettingsItem(
                        icon = Icons.Default.Code,
                        title = "每任务并发分片数",
                        subtitle = "16",
                        onClick = { /* TODO: 设置分片并发数 */ }
                    )
                }
            }

            // 浏览器插件
            item {
                SettingsSection(title = "浏览器插件") {
                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = "URL Scheme",
                        subtitle = "m3u8downloader://add",
                        onClick = { /* TODO: 复制到剪贴板 */ }
                    )
                }
            }

            // 外观
            item {
                SettingsSection(title = "外观") {
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        title = "深色模式",
                        subtitle = "跟随系统",
                        onClick = { /* TODO: 切换深色模式 */ }
                    )
                }
            }

            // 关于
            item {
                SettingsSection(title = "关于") {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "版本",
                        subtitle = "1.0.0",
                        onClick = { }
                    )
                }
            }

            // 占位项，消除未完成感
            item {
                SettingsSection(title = "更多功能") {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "敬请期待",
                        subtitle = "更多设置将在后续版本中添加",
                        onClick = { }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
