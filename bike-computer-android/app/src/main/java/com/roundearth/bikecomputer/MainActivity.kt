package com.roundearth.bikecomputer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.roundearth.bikecomputer.ui.dashboard.BikeViewModel
import com.roundearth.bikecomputer.ui.dashboard.DashboardScreen
import com.roundearth.bikecomputer.ui.sensors.SensorScreen
import com.roundearth.bikecomputer.ui.sensors.SensorViewModel
import com.roundearth.bikecomputer.ui.settings.SettingsScreen
import com.roundearth.bikecomputer.ui.settings.SettingsViewModel
import com.roundearth.bikecomputer.ui.theme.BikeComputerTheme

class MainActivity : ComponentActivity() {

    private val blePermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BikeComputerTheme {
                val app = application as BikeApplication
                val bikeVm: BikeViewModel = viewModel(factory = BikeViewModel.Factory(app.repository))
                val settingsVm: SettingsViewModel =
                    viewModel(factory = SettingsViewModel.Factory(app.prefs, app.repository))
                val sensorVm: SensorViewModel =
                    viewModel(factory = SensorViewModel.Factory(app.bleSource, app.prefs))
                val nav = rememberNavController()

                // Request BLE permissions, then start scanning/recording — but only
                // start the BLE source once every required permission is granted.
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    if (grants.values.all { it }) app.repository.start()
                }

                LaunchedEffect(Unit) {
                    if (app.usesBle) launcher.launch(blePermissions) else app.repository.start()
                }

                NavHost(
                    navController = nav,
                    startDestination = "dashboard",
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                ) {
                    composable("dashboard") {
                        DashboardScreen(viewModel = bikeVm, onSettingsClick = { nav.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsVm,
                            onBack = { nav.popBackStack() },
                            onSensorsClick = { nav.navigate("sensors") },
                        )
                    }
                    composable("sensors") {
                        SensorScreen(viewModel = sensorVm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
