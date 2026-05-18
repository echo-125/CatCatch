package com.catcatch.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航 Tab 定义
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "主页", Icons.Filled.Home)
    data object Browser : Screen("browser", "窗口", Icons.Filled.Public)
    data object Downloads : Screen("downloads", "下载", Icons.Filled.Download)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)
}
