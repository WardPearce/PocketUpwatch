package com.pocketupwatch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun MaintenanceControls(until: Long, now: Long, onChange: (Long) -> Unit) {
    DetailSection(Modifier.testTag("service-maintenance")) {
        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
        Text(if (until > now) "Until ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(until))}" else "Off", style = MaterialTheme.typography.bodyLarge)
        Text("Scheduled checks and alerts pause until maintenance ends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1L, 4L, 24L).forEach { hours ->
                AppSecondaryButton(onClick = { onChange(now + hours * 3_600_000) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text("${hours}h") }
            }
        }
        if (until > now) TextButton(onClick = { onChange(0) }) { Text("End maintenance") }
        if (until > now) Text("Starting a window replaces the current end time.", style = MaterialTheme.typography.bodySmall)
    }
}
