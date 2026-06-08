package com.roundearth.bikecomputer.ui.sensors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roundearth.bikecomputer.data.DiscoveredSensor
import com.roundearth.bikecomputer.ui.theme.Background
import com.roundearth.bikecomputer.ui.theme.Divider
import com.roundearth.bikecomputer.ui.theme.Green
import com.roundearth.bikecomputer.ui.theme.Surface
import com.roundearth.bikecomputer.ui.theme.TextPrimary
import com.roundearth.bikecomputer.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorScreen(viewModel: SensorViewModel, onBack: () -> Unit) {
    val sensors by viewModel.sensors.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        TopAppBar(
            title = { Text("Sensors", color = TextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
        )

        if (sensors.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("◌ Scanning for sensors…", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sensors, key = { it.address }) { sensor ->
                    SensorRow(sensor, onClick = { viewModel.togglePair(sensor.address) })
                }
            }
        }
    }
}

@Composable
private fun SensorRow(sensor: DiscoveredSensor, onClick: () -> Unit) {
    val accent = if (sensor.paired) Green else Divider

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .border(1.dp, accent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(sensor.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "${sensor.address}  ·  ${sensor.rssi} dBm",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            sensor.firmwareRevision?.let { fw ->
                Text("fw $fw", color = TextSecondary, fontSize = 11.sp)
            }
        }
        StatusChip(sensor)
    }
}

@Composable
private fun StatusChip(sensor: DiscoveredSensor) {
    val (text, color) = when {
        sensor.connected -> "● CONNECTED" to Green
        sensor.paired -> "◌ PAIRED" to TextSecondary
        else -> "+ PAIR" to TextPrimary
    }
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    )
}
