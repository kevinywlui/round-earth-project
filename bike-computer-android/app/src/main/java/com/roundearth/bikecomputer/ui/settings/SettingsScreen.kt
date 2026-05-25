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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var circumferenceText by remember(state.wheelCircumferenceM) {
        mutableStateOf("%.3f".format(state.wheelCircumferenceM))
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

        Column(modifier = Modifier.padding(20.dp)) {

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
                                circumferenceText = "%.3f".format(value)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                            border = BorderStroke(1.dp, Divider),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, fontSize = 12.sp)
                                Text("${"%.3f".format(value)}m", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                    if (pair.size < 2) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
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
