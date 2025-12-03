package com.example.wifi_analyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.wifiinspector.ui.screens.HostDetailsScreen

// Простое определение роутов
sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object HostDetails : Screen("details/{ip}") { // Роут с аргументом
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

    NavHost(navController = navController, startDestination = Screen.Dashboard.route) {

        // --- ЭКРАН 1: ИНСПЕКТОР СЕТИ ---
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onHostClick = { ip ->
                    // Переход на экран деталей при клике
                    navController.navigate(Screen.HostDetails.createRoute(ip))
                }
            )
        }

        // --- ЭКРАN 2: СКАНЕР ПОРТОВ ---
        composable(
            route = Screen.HostDetails.route,
            arguments = listOf(navArgument("ip") { type = NavType.StringType })
        ) { //backStackEntry ->
//            // Извлекаем IP из аргументов навигации
//            val ipAddress = backStackEntry.arguments?.getString("ip") ?: "0.0.0.0"

            HostDetailsScreen(
                viewModel = viewModel(),
                onBackClick = {
                    navController.popBackStack() // Кнопка "Назад"
                },
            )
        }
    }
}