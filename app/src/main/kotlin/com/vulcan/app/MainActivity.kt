package com.vulcan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.vulcan.app.service.VulcanCoreService
import com.vulcan.app.ui.screens.*
import com.vulcan.app.ui.theme.VulcanTheme
import com.vulcan.app.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Start VulcanCoreService if not already running
        if (!VulcanCoreService.isRunning(this)) {
            startForegroundService(
                android.content.Intent(this, VulcanCoreService::class.java)
            )
        }

        setContent {
            VulcanTheme {
                VulcanApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun VulcanApp(viewModel: MainViewModel) {
    val navController = rememberNavController()

    // Show setup wizard on first run
    if (viewModel.isFirstRun) {
        SetupWizardScreen(
            viewModel  = viewModel,
            onComplete = { /* navController already at home */ }
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            VulcanBottomNav(navController = navController)
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel          = viewModel,
                    onNavigateToStore  = { navController.navigate(Screen.Store.route) },
                    onOpenLogs         = { appId ->
                        navController.navigate("logs/$appId")
                    }
                )
            }

            composable(Screen.Store.route) {
                StoreScreen(viewModel = viewModel)
            }

            composable(Screen.Network.route) {
                NetworkScreen(viewModel = viewModel)
            }

            composable(Screen.Metrics.route) {
                MetricsScreen(viewModel = viewModel)
            }

            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }

            composable("logs/{appId}") { backStack ->
                val appId = backStack.arguments?.getString("appId") ?: return@composable
                LogScreen(appId = appId, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
fun VulcanBottomNav(navController: androidx.navigation.NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Don't show bottom nav on log screen
    if (currentDestination?.route?.startsWith("logs/") == true) return

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = androidx.compose.ui.unit.dp * 0
    ) {
        Screen.bottomNavItems.forEach { screen ->
            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                icon     = { Icon(screen.icon, contentDescription = screen.label) },
                label    = { Text(screen.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                selected = selected,
                onClick  = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState    = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor   = com.vulcan.app.ui.theme.VulcanColors.ForgeOrange,
                    selectedTextColor   = com.vulcan.app.ui.theme.VulcanColors.ForgeOrange,
                    indicatorColor      = com.vulcan.app.ui.theme.VulcanColors.ForgeOrange.copy(alpha = 0.15f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
