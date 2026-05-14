package com.catcatch.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.catcatch.domain.model.TaskStatus
import com.catcatch.ui.download.DownloadScreen
import com.catcatch.ui.home.HomeScreen
import com.catcatch.ui.home.HomeViewModel
import com.catcatch.ui.settings.SettingsScreen

/**
 * 主界面容器，包含底部导航栏和 NavHost
 */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val tabs = listOf(Screen.Home, Screen.Downloads, Screen.Settings)

    // 共享 ViewModel 用于获取任务数量
    val sharedViewModel: HomeViewModel = hiltViewModel()
    val taskListState by sharedViewModel.taskListState.collectAsState()
    val activeTaskCount = taskListState.tasks.count {
        it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PENDING || it.status == TaskStatus.MERGING
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                tabs.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            if (screen == Screen.Downloads && activeTaskCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(text = if (activeTaskCount > 99) "99+" else "$activeTaskCount")
                                        }
                                    }
                                ) {
                                    Icon(screen.icon, contentDescription = screen.title)
                                }
                            } else {
                                Icon(screen.icon, contentDescription = screen.title)
                            }
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Downloads.route) { DownloadScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
