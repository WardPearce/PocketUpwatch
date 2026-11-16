package com.pocketupwatch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun MaintenanceControls(until: Long, now: Long, durationHours: Int = 0, onChange: (Long, Int) -> Unit) {
    val activeHours = if (durationHours in listOf(1, 4, 24)) durationHours else listOf(1, 4, 24).firstOrNull { until - now <= it * 3_600_000L } ?: 24
    DetailSection(Modifier.testTag("service-maintenance")) {
        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
        Text(if (until > now) "Until ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(until))}" else "Off", style = MaterialTheme.typography.bodyLarge)
        Text("Scheduled checks and alerts pause until maintenance ends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1L, 4L, 24L).forEach { hours ->
                val selected = until > now && activeHours == hours.toInt()
                FilterChip(selected = selected, onClick = { if (selected) onChange(0, 0) else onChange(now + hours * 3_600_000, hours.toInt()) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), label = { Text("${hours}h") })
            }
        }
        Text("Tap a duration to start maintenance. Tap it again to turn it off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
