package com.roundearth.bikecomputer.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.Locale
import com.roundearth.bikecomputer.BikeApplication
import com.roundearth.bikecomputer.data.DeclinationProvider
import com.roundearth.bikecomputer.ui.theme.Divider
import com.roundearth.bikecomputer.ui.theme.Green
import com.roundearth.bikecomputer.ui.theme.Surface
import com.roundearth.bikecomputer.ui.theme.TextPrimary
import com.roundearth.bikecomputer.ui.theme.TextSecondary

private val WHEEL_PRESETS = listOf(
    "700c × 23mm" to 2.070,
    "700c × 25mm" to 2.096,
    "700c × 28mm" to 2.136,
    "700c × 32mm" to 2.173,
    "29\"" to 2.288,
    "27.5\"" to 2.199,
    "26\"" to 2.073,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSensorsClick: () -> Unit,
    onLogsClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Always format the editable fields with '.' (Locale.US) because the SET handlers
    // parse with toDoubleOrNull, which only accepts '.'. Default-locale formatting would
    // render "2,096" on comma-decimal locales and the value could never be saved back.
    var circumferenceText by remember(state.wheelCircumferenceM) {
        mutableStateOf(String.format(Locale.US, "%.3f", state.wheelCircumferenceM))
    }
    // Sign is chosen with the E/W toggle, so the field itself stays positive —
    // many soft keyboards don't offer a minus key on the decimal layout.
    var declinationText by remember(state.magneticDeclinationDeg) {
        mutableStateOf(String.format(Locale.US, "%.1f", kotlin.math.abs(state.magneticDeclinationDeg)))
    }
    var declinationEast by remember(state.magneticDeclinationDeg) {
        mutableStateOf(state.magneticDeclinationDeg >= 0)
    }
    var offsetText by remember(state.headingOffsetDeg) {
        mutableStateOf(String.format(Locale.US, "%.0f", state.headingOffsetDeg))
    }

    val app = context.applicationContext as BikeApplication

    // Auto-declination: ask for coarse location once, then compute from the last known fix.
    // Saving updates the pref, which re-seeds the field above via remember(state...).
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Location permission needed to auto-detect", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val declination = DeclinationProvider(context).currentDeclinationDegrees(System.currentTimeMillis())
        if (declination == null) {
            Toast.makeText(context, "No location fix yet — enable location and retry", Toast.LENGTH_LONG).show()
        } else {
            viewModel.setMagneticDeclination(declination.toDouble())
            Toast.makeText(
                context,
                String.format(Locale.US, "Declination set to %.1f°%s", kotlin.math.abs(declination), if (declination >= 0) "E" else "W"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        TopAppBar(
            title = { Text("Settings", color = TextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {

            SectionLabel("SENSORS")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onSensorsClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                border = BorderStroke(1.dp, Divider),
            ) {
                Text("Manage Bluetooth Sensors")
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("DIAGNOSTICS")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onLogsClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                border = BorderStroke(1.dp, Divider),
            ) {
                Text("View Logs (app + firmware)")
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("UNITS")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("km/h", color = TextPrimary, fontSize = 14.sp)
                Switch(
                    checked = state.useImperial,
                    onCheckedChange = { viewModel.setUseImperial(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = Green),
                )
                Text("mph", color = TextPrimary, fontSize = 14.sp)
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("WHEEL CIRCUMFERENCE")
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = circumferenceText,
                onValueChange = { circumferenceText = it },
                label = { Text("Meters") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        circumferenceText.toDoubleOrNull()
                            ?.coerceIn(0.5, 5.0)
                            ?.let { viewModel.setWheelCircumference(it) }
                    }) { Text("SET", color = Green) }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Green,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = Green,
                    unfocusedLabelColor = TextSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("COMMON SIZES")
            Spacer(Modifier.height(10.dp))

            WHEEL_PRESETS.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { (label, value) ->
                        OutlinedButton(
                            onClick = {
                                viewModel.setWheelCircumference(value)
                                circumferenceText = String.format(Locale.US, "%.3f", value)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                            border = BorderStroke(1.dp, Divider),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, fontSize = 12.sp)
                                Text("${String.format(Locale.US, "%.3f", value)}m", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                    if (pair.size < 2) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("MAGNETIC DECLINATION")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Degrees to convert magnetic heading to true north. Pick east " +
                    "or west, then enter the magnitude. Look up your location at " +
                    "magnetic-declination.com.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("West", color = TextPrimary, fontSize = 14.sp)
                Switch(
                    checked = declinationEast,
                    onCheckedChange = { declinationEast = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = Green),
                )
                Text("East", color = TextPrimary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = declinationText,
                onValueChange = { declinationText = it },
                label = { Text("Degrees") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        declinationText.toDoubleOrNull()
                            ?.coerceIn(0.0, 180.0)
                            ?.let { mag -> viewModel.setMagneticDeclination(if (declinationEast) mag else -mag) }
                    }) { Text("SET", color = Green) }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Green,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = Green,
                    unfocusedLabelColor = TextSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                border = BorderStroke(1.dp, Divider),
            ) { Text("AUTO-DETECT FROM LOCATION", fontSize = 12.sp) }

            Spacer(Modifier.height(28.dp))
            SectionLabel("MOUNTING OFFSET")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Corrects for how the phone sits on the mount: the angle between " +
                    "the phone and the bike's forward direction. Point the bike due north " +
                    "and tap CALIBRATE, or enter the angle directly.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it },
                label = { Text("Degrees") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        offsetText.toDoubleOrNull()
                            ?.let { ((it % 360.0) + 360.0) % 360.0 }
                            ?.let { viewModel.setHeadingOffset(it) }
                    }) { Text("SET", color = Green) }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Green,
                    unfocusedBorderColor = Divider,
                    focusedLabelColor = Green,
                    unfocusedLabelColor = TextSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val raw = app.rawHeadingDegrees
                    when {
                        !app.hasHeadingSensor ->
                            Toast.makeText(context, "No compass sensor on this device", Toast.LENGTH_SHORT).show()
                        raw.isNaN() ->
                            Toast.makeText(context, "No heading yet — wait for the compass", Toast.LENGTH_SHORT).show()
                        else -> {
                            // Bike is pointing north, so the raw azimuth IS the mounting offset:
                            // subtracting it makes the corrected heading read 0° (north) here.
                            viewModel.setHeadingOffset(raw.toDouble())
                            Toast.makeText(context, "Calibrated", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                border = BorderStroke(1.dp, Divider),
            ) { Text("CALIBRATE: BIKE POINTING NORTH", fontSize = 12.sp) }

            Spacer(Modifier.height(20.dp))
            SectionLabel("RECORDED DATA")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${state.recordedEventCount} revolution events · ${state.sessions.size} rides",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                        val file = File(dir, "bike-data.csv")
                        viewModel.exportCsv(
                            file,
                            onReady = { written ->
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", written,
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    // clipData ensures the read grant reaches receivers
                                    // that look there instead of EXTRA_STREAM.
                                    clipData = ClipData.newRawUri("bike-data.csv", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Export bike data"))
                            },
                            onError = {
                                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                    border = BorderStroke(1.dp, Divider),
                ) { Text("EXPORT CSV", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = { viewModel.clearHistory() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336)),
                    border = BorderStroke(1.dp, Divider),
                ) { Text("CLEAR", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Green,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}
