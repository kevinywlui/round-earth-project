package com.roundearth.bikecomputer.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roundearth.bikecomputer.data.ConnectionState
import com.roundearth.bikecomputer.ui.BikeUiState
import com.roundearth.bikecomputer.ui.theme.Divider
import com.roundearth.bikecomputer.ui.theme.Green
import com.roundearth.bikecomputer.ui.theme.TextPrimary
import com.roundearth.bikecomputer.ui.theme.TextSecondary

@Composable
fun DashboardScreen(viewModel: BikeViewModel, onSettingsClick: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TopBar(onSettingsClick)
        Spacer(Modifier.height(12.dp))
        BearingRow(state)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(16.dp))
        SpeedDisplay(state)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(16.dp))
        SecondaryMetricsRow(state)
        Spacer(Modifier.weight(1f))
        StatusBar(state.connectionState)
    }
}

@Composable
private fun TopBar(onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "☀️ BIKE COMPUTER",
            color = Green,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
        }
    }
}

@Composable
private fun BearingRow(state: BikeUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompassCell(
            label = "TRUE",
            bearingDegrees = state.trueBearingDegrees,
            cardinal = state.trueBearingCardinal,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Divider),
        )
        CompassCell(
            label = "MAGNETIC",
            bearingDegrees = state.bearingDegrees,
            cardinal = state.bearingCardinal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompassCell(
    label: String,
    bearingDegrees: Float,
    cardinal: String,
    modifier: Modifier = Modifier,
) {
    val animatedBearing by animateFloatAsState(
        targetValue = bearingDegrees,
        animationSpec = tween(durationMillis = 900),
        label = "bearing-$label",
    )

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompassNeedle(bearingDegrees = animatedBearing, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "%03.0f°  %s".format(bearingDegrees, cardinal),
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            )
            Text(text = label, color = TextSecondary, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun CompassNeedle(bearingDegrees: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f

        drawCircle(color = Color(0xFF1E1E1E), radius = r)
        drawCircle(color = Color(0xFF333333), radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))

        rotate(degrees = bearingDegrees, pivot = center) {
            drawLine(
                color = Color(0xFFF44336),
                start = center,
                end = Offset(center.x, center.y - r * 0.75f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFF666666),
                start = center,
                end = Offset(center.x, center.y + r * 0.55f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = TextPrimary, radius = 2.5.dp.toPx(), center = center)
    }
}

@Composable
private fun SpeedDisplay(state: BikeUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "%.1f".format(state.speed),
            color = Green,
            fontSize = 88.sp,
            fontWeight = FontWeight.Thin,
            textAlign = TextAlign.Center,
            lineHeight = 88.sp,
        )
        Text(text = state.speedLabel, color = TextSecondary, fontSize = 13.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun SecondaryMetricsRow(state: BikeUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        MetricCell(
            value = "%.0f".format(state.cadenceRpm),
            label = "RPM",
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Divider),
        )
        MetricCell(
            value = "%.1f".format(state.odometer),
            label = state.distanceLabel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = value, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Text(text = label, color = TextSecondary, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun StatusBar(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "● CONNECTED"
        ConnectionState.SCANNING -> Color(0xFFFFC107) to "◌ SCANNING"
        ConnectionState.SIMULATED -> Color(0xFF2196F3) to "◉ SIMULATED"
        ConnectionState.DISCONNECTED -> TextSecondary to "○ DISCONNECTED"
    }
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}
