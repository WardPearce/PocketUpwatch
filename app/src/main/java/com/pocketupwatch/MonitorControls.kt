package com.pocketupwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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
        hours.takeIf { it > 0 }?.let { "$it ${if (it == 1) "hour" else "hours"}" },
        minutes.takeIf { it > 0 }?.let { "$it min" },
        remainingSeconds.takeIf { it > 0 }?.let { "$it sec" }
    ).joinToString(" ").ifEmpty { "0 sec" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProtocolPicker(protocol: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width = 124.dp + 36.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
    ExposedDropdownMenuBox(expanded && enabled, onExpandedChange = { if (enabled) expanded = !expanded }, modifier = Modifier.width(width)) {
        OutlinedTextField(protocol.uppercase(), {}, readOnly = true, enabled = enabled, singleLine = true,
            label = { Text("Protocol") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).heightIn(min = 64.dp).semantics { contentDescription = "Protocol" })
        ExposedDropdownMenu(expanded && enabled, { expanded = false }) {
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

@Composable internal fun ConfirmationSlider(checks: Int, recovery: Boolean, enabled: Boolean = true, onChange: (Int) -> Unit) {
    val state = "$checks ${if (recovery) "successful" else "failed"} ${if (checks == 1) "check" else "checks"}"
    Column(Modifier.fillMaxWidth()) {
        Text("${if (recovery) "Recover" else "Mark down"} after $state", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = checks.toFloat().coerceIn(1f, 20f), valueRange = 1f..20f, steps = 18, enabled = enabled,
            onValueChange = { position -> position.roundToInt().coerceIn(1, 20).takeIf { it != checks }?.let(onChange) },
            modifier = Modifier.fillMaxWidth().testTag(if (recovery) "recovery-threshold-slider" else "failure-threshold-slider").semantics {
                contentDescription = if (recovery) "Recovery confirmation" else "Failure confirmation"
                stateDescription = state
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1 check", style = MaterialTheme.typography.bodySmall)
            Text("20 checks", style = MaterialTheme.typography.bodySmall)
        }
        Text("Checks must ${if (recovery) "pass" else "fail"} in a row.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun formatReminderInterval(minutes: Int): String = if (minutes >= 1440 && minutes % 1440 == 0) {
    val days = minutes / 1440
    "$days ${if (days == 1) "day" else "days"}"
} else formatInterval(minutes * 60)

@Composable internal fun RepeatAlertSlider(minutes: Int, originalMinutes: Int?, enabled: Boolean = true, onChange: (Int) -> Unit) {
    val intervals = remember(originalMinutes) {
        (listOf(5, 10, 15, 30, 60, 120, 240, 360, 720, 1440, 2880, 4320, 10080) +
            listOfNotNull(originalMinutes?.let(AlertReminders::normalize)?.takeIf { it in AlertReminders.MIN_MINUTES..AlertReminders.MAX_MINUTES })).distinct().sorted()
    }
    var lastInterval by rememberSaveable(originalMinutes) { mutableIntStateOf(minutes.takeIf { it > 0 }?.let(AlertReminders::normalize) ?: AlertReminders.DEFAULT_MINUTES) }
    EditorToggle("Repeat alerts", "Remind me if an outage continues.", minutes > 0, enabled) { checked ->
        if (!checked) lastInterval = AlertReminders.normalize(minutes)
        onChange(if (checked) lastInterval else 0)
    }
    if (minutes > 0) Column(Modifier.fillMaxWidth()) {
        Text("Remind every ${formatReminderInterval(minutes)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = intervals.indexOf(AlertReminders.normalize(minutes)).coerceAtLeast(0).toFloat(),
            onValueChange = { position ->
                lastInterval = intervals[position.roundToInt().coerceIn(intervals.indices)]
                onChange(lastInterval)
            },
            enabled = enabled,
            valueRange = 0f..intervals.lastIndex.toFloat(), steps = intervals.size - 2,
            modifier = Modifier.fillMaxWidth().testTag("repeat-alert-slider").semantics {
                contentDescription = "Alert reminder interval"
                stateDescription = formatReminderInterval(minutes)
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("5 min", style = MaterialTheme.typography.bodySmall)
            Text("7 days", style = MaterialTheme.typography.bodySmall)
        }
        Text("Android may delay reminders.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val strong = status == "UP" || status == "DOWN"
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val tint = when (status) {
        "UP" -> Color(0xff005c3b)
        "DOWN" -> Color(0xff8c1d18)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val outline = if (dark && strong) {
        if (status == "UP") Color(0xff83d9af) else Color(0xffffb4ab)
    } else if (strong) tint else tint.copy(alpha = 0.45f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (strong) tint else tint.copy(alpha = 0.10f),
        contentColor = if (strong) Color.White else tint,
        border = BorderStroke(1.dp, outline)
    ) {
        Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium)
    }
}
