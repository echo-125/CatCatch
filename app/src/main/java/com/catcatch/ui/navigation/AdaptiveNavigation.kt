package com.catcatch.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController

/**
 * 自适应导航外壳
 * 始终使用底部 NavigationBar
 */
@Composable
fun AdaptiveNavigation(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController,
    tabs: List<Screen>,
    activeTaskCount: Int,
    content: @Composable () -> Unit
) {
    Scaffold(
        bottomBar = {
            BottomNavBar(
                navController = navController,
                tabs = tabs,
                activeTaskCount = activeTaskCount
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            content()
        }
    }
}
