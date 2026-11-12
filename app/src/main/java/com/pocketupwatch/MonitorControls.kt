package com.pocketupwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun formatInterval(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val remainingSeconds = total % 60
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { "$it${if (it == 1) "hour" else "hours"}" },
        minutes.takeIf { it > 0 }?.let { "${it}min" },
        remainingSeconds.takeIf { it > 0 }?.let { "${it}sec" }
    ).joinToString(" ").ifEmpty { "0sec" }
}

@Composable internal fun ProtocolPicker(protocol: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppSecondaryButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "Protocol" },
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Text(protocol.uppercase())
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            listOf("https", "http", "tcp", "ping", "dns", "tls").forEach { choice ->
                DropdownMenuItem(text = { Text(choice.uppercase()) }, onClick = { onChange(choice); expanded = false })
            }
        }
    }
}

@Composable internal fun IntervalSlider(seconds: Int, originalSeconds: Int?, enabled: Boolean = true, onChange: (Int) -> Unit) {
    val intervals = remember(originalSeconds) {
        (listOf(300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400) +
            listOfNotNull(originalSeconds?.takeIf { it in 300..86400 })).distinct().sorted()
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Check interval: ${formatInterval(seconds)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            enabled = enabled,
            value = intervals.indexOf(seconds).coerceAtLeast(0).toFloat(),
            onValueChange = { onChange(intervals[it.roundToInt().coerceIn(intervals.indices)]) },
            valueRange = 0f..intervals.lastIndex.toFloat(),
            steps = intervals.size - 2,
            modifier = Modifier.fillMaxWidth().testTag("interval-slider").semantics {
                contentDescription = "Check interval"
                stateDescription = formatInterval(seconds)
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatInterval(intervals.first()), style = MaterialTheme.typography.bodySmall)
            Text(formatInterval(intervals.last()), style = MaterialTheme.typography.bodySmall)
        }

    }
}

@Composable internal fun CheckNowButton(checking: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppButton(enabled = !checking, modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checking) CircularProgressIndicator(
                modifier = Modifier.size(16.dp).testTag("check-progress"),
                strokeWidth = 2.dp,
                color = LocalContentColor.current
            )
            Text(if (checking) "Checking…" else "Check now")
        }
    }
}

@Composable internal fun StatusBadge(status: String) {
    val tint = when (status) {
        "UP" -> Color(0xff16835c)
        "DOWN" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.10f),
        contentColor = tint,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
    ) {
        Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}
