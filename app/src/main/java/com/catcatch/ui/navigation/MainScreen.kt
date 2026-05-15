package com.catcatch.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.download.DownloadScreen
import com.catcatch.ui.home.HomeScreen
import com.catcatch.ui.home.HomeViewModel
import com.catcatch.ui.settings.SettingsScreen

/**
 * 主界面容器
 * 竖屏时底部NavigationBar，横屏时左侧NavigationRail
 */
@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass
) {
    val navController = rememberNavController()
    val tabs = listOf(Screen.Home, Screen.Downloads, Screen.Settings)

    // 共享 ViewModel，避免重复创建导致 Deep Link 数据被多次处理
    val sharedViewModel: HomeViewModel = hiltViewModel()
    val taskListState by sharedViewModel.taskListState.collectAsState()
    val activeTaskCount = taskListState.tasks.count {
        it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PENDING || it.status == TaskStatus.MERGING || it.status == TaskStatus.TRANSCODING
    }

    AdaptiveNavigation(
        windowSizeClass = windowSizeClass,
        navController = navController,
        tabs = tabs,
        activeTaskCount = activeTaskCount
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Home.route) { HomeScreen(viewModel = sharedViewModel) }
            composable(Screen.Downloads.route) { DownloadScreen(viewModel = sharedViewModel) }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
