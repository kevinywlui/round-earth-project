package com.roundearth.bikecomputer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.roundearth.bikecomputer.ui.dashboard.BikeViewModel
import com.roundearth.bikecomputer.ui.dashboard.DashboardScreen
import com.roundearth.bikecomputer.ui.settings.SettingsScreen
import com.roundearth.bikecomputer.ui.settings.SettingsViewModel
import com.roundearth.bikecomputer.ui.theme.BikeComputerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BikeComputerTheme {
                val app = application as BikeApplication
                val bikeVm: BikeViewModel = viewModel(factory = BikeViewModel.Factory(app.repository))
                val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app.prefs))
                val nav = rememberNavController()

                NavHost(
                    navController = nav,
                    startDestination = "dashboard",
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                ) {
                    composable("dashboard") {
                        DashboardScreen(viewModel = bikeVm, onSettingsClick = { nav.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(viewModel = settingsVm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
