package com.roundearth.bikecomputer.ui.logs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roundearth.bikecomputer.data.diagnostics.LogBus
import com.roundearth.bikecomputer.ui.theme.Green
import com.roundearth.bikecomputer.ui.theme.Surface
import com.roundearth.bikecomputer.ui.theme.TextPrimary
import com.roundearth.bikecomputer.ui.theme.TextSecondary
import com.roundearth.bikecomputer.ui.theme.Warn
import java.text.SimpleDateFormat
import java.util.Locale

private enum class LogFilter { ALL, APP, FW }

/**
 * In-app diagnostics view: the app's own BLE/state-machine logs and the firmware's debug lines
 * (streamed over BLE), merged and timestamped, so a connection can be inspected from both ends
 * without `adb logcat` or a serial console. Filter by source, share the buffer, or clear it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val lines by LogBus.lines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    val shown = remember(lines, filter) {
        when (filter) {
            LogFilter.ALL -> lines
            LogFilter.APP -> lines.filter { it.source == LogBus.Source.APP }
            LogFilter.FW -> lines.filter { it.source == LogBus.Source.FW }
        }
    }

    // Follow the tail as new lines arrive (debug log convention).
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        TopAppBar(
            title = { Text("Diagnostics", color = TextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
            },
            actions = {
                IconButton(onClick = { shareLogs(context, shown, timeFmt) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share logs", tint = TextSecondary)
                }
                IconButton(onClick = { LogBus.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = TextSecondary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach { f ->
                val count = when (f) {
                    LogFilter.ALL -> lines.size
                    LogFilter.APP -> lines.count { it.source == LogBus.Source.APP }
                    LogFilter.FW -> lines.count { it.source == LogBus.Source.FW }
                }
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.name} ($count)", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Green,
                        selectedLabelColor = Color(0xFF0A0A0A),
                        labelColor = TextSecondary,
                    ),
                )
            }
        }

        if (shown.isEmpty()) {
            Text(
                text = "No logs yet. Connect a sensor to see app and firmware diagnostics here.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                items(shown) { line -> LogRow(line, timeFmt.format(line.timestampMs)) }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogBus.Line, time: String) {
    // Firmware lines in green to set them apart from the phone's; warnings/errors in red.
    val color = when {
        line.level == LogBus.Level.W || line.level == LogBus.Level.E -> Warn
        line.source == LogBus.Source.FW -> Green
        else -> TextPrimary
    }
    val tag = if (line.source == LogBus.Source.FW) "FW " else "APP"
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(time, color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
            "  $tag  ",
            color = if (line.source == LogBus.Source.FW) Green else TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(line.message, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Fires a share intent with the (filtered) buffer as plain text. */
private fun shareLogs(
    context: android.content.Context,
    lines: List<LogBus.Line>,
    timeFmt: SimpleDateFormat,
) {
    if (lines.isEmpty()) return
    val text = buildString {
        for (l in lines) {
            val src = if (l.source == LogBus.Source.FW) "FW" else "APP"
            append(timeFmt.format(l.timestampMs)).append(' ')
                .append(src).append('/').append(l.level.name).append(' ')
                .append(l.message).append('\n')
        }
    }
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Bike Computer diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(share, "Share diagnostics"))
}
