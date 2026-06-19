package com.roundearth.bikecomputer

import android.Manifest
import android.content.Intent
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.roundearth.bikecomputer.ui.dashboard.BikeViewModel
import com.roundearth.bikecomputer.ui.dashboard.DashboardScreen
import com.roundearth.bikecomputer.ui.logs.LogScreen
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

    // Best-effort extras requested alongside the BLE permissions but never gating collection:
    //  - POST_NOTIFICATIONS (API 33+): a denial only hides the foreground notice; the service runs.
    //  - ACCESS_FINE_LOCATION: enables the optional GPS anchoring log; a denial just skips GPS.
    // (On API < 31 fine location is already a *gating* BLE-scan permission in blePermissions.)
    private val optionalPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

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

                // Request BLE (+ notification) permissions, then start the foreground collection
                // service — but only once the *BLE* permissions are granted. The notification grant
                // is best-effort and intentionally not part of the gate: the service runs without it.
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    if (blePermissions.all { grants[it] == true }) {
                        ContextCompat.startForegroundService(
                            app, Intent(app, CollectionService::class.java)
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    launcher.launch(blePermissions + optionalPermissions)
                }

                NavHost(
                    navController = nav,
                    startDestination = "dashboard",
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                ) {
                    composable("dashboard") {
                        DashboardScreen(
                            viewModel = bikeVm,
                            onSettingsClick = { nav.navigate("settings") },
                            onStatusClick = { nav.navigate("sensors") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsVm,
                            onBack = { nav.popBackStack() },
                            onSensorsClick = { nav.navigate("sensors") },
                            onLogsClick = { nav.navigate("logs") },
                        )
                    }
                    composable("sensors") {
                        SensorScreen(viewModel = sensorVm, onBack = { nav.popBackStack() })
                    }
                    composable("logs") {
                        LogScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
