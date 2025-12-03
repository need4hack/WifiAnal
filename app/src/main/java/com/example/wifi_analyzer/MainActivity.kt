package com.example.wifi_analyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifi_analyzer.ui.theme.Wifi_analyzerTheme
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.wifi_analyzer.ui.theme.screens.DashboardScreen
//import com.example.wifi_analyzer.ui.theme.screens.HostDetailsScreen
import com.example.wifiinspector.ui.screens.HostDetailsScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.wifi_analyzer.ui.theme.screens.HistoryScreen

// Простое определение роутов
sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    object Dashboard : Screen("dashboard", "Главная", Icons.Default.Home)
    object History : Screen("history", "История", Icons.Default.List)
    object HostDetails : Screen("details/{ip}", "Детали") { // Роут с аргументом
        fun createRoute(ip: String) = "details/$ip"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Wifi_analyzerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Список экранов для нижней панели
    val bottomNavItems = listOf(
        Screen.Dashboard,
        Screen.History
    )

    Scaffold(
        bottomBar = {
            // Показываем бар только на главных экранах (не на деталях)
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            // Проверяем, является ли текущий экран одним из тех, что в меню
            if (currentRoute == Screen.Dashboard.route || currentRoute == Screen.History.route) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon!!, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    // Чтобы не плодить копии экранов в стеке
                                    popUpTo(navController.graph.startDestinationId) {
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding) // Важно! Отступ для контента
        ) {
            // Вкладка 1: Дашборд
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onHostClick = { ip ->
                        navController.navigate(Screen.HostDetails.createRoute(ip))
                    }
                )
            }

            // Вкладка 2: История (НОВОЕ)
            composable(Screen.History.route) {
                HistoryScreen()
            }

            // Экран деталей (без нижней панели)
            composable(
                route = Screen.HostDetails.route,
                arguments = listOf(navArgument("ip") { type = NavType.StringType })
            ) {
                HostDetailsScreen(
                    viewModel = viewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}