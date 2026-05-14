package com.catcatch.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * 自适应导航外壳
 * - 竖屏: 底部 NavigationBar
 * - 横屏 (宽度<600dp): 底部 NavigationBar
 * - 横屏 (600-840dp): 左侧 NavigationRail
 * - 宽屏 (>840dp): 左侧 NavigationRail
 */
@Composable
fun AdaptiveNavigation(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController,
    tabs: List<Screen>,
    activeTaskCount: Int,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val widthClass = windowSizeClass.widthSizeClass

    when {
        // 竖屏 或 横屏宽度<600dp: 底部 NavigationBar
        isPortrait || widthClass == WindowWidthSizeClass.Compact -> {
            Scaffold(
                bottomBar = {
                    BottomNavBar(
                        navController = navController,
                        tabs = tabs,
                        activeTaskCount = activeTaskCount
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }

        // 横屏 600-840dp: 左侧 NavigationRail
        widthClass == WindowWidthSizeClass.Medium -> {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRailContent(
                    navController = navController,
                    tabs = tabs,
                    activeTaskCount = activeTaskCount
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    content()
                }
            }
        }

        // 宽屏 >840dp: 左侧 NavigationRail
        else -> {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRailContent(
                    navController = navController,
                    tabs = tabs,
                    activeTaskCount = activeTaskCount
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}
