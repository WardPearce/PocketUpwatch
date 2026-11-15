package com.pocketupwatch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun CsvExportButtons(id: Long, dao: MonitorDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var incidents by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = if (incidents) BackupCodec.incidentsCsv(dao.exportIncidents(id)) else BackupCodec.historyCsv(dao.exportHistory(id))
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(data) }
                }
                message = "CSV saved."
            } catch (_: Exception) { message = "Couldn't save the CSV file. Try again." }
        }
    }
    var exportMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        AppSecondaryButton(onClick = { exportMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Export data", Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
            DropdownMenuItem(text = { Text("Export checks as CSV") }, onClick = { exportMenu = false; incidents = false; export.launch("PocketUpwatch-$id-checks.csv") })
            DropdownMenuItem(text = { Text("Export incidents as CSV") }, onClick = { exportMenu = false; incidents = true; export.launch("PocketUpwatch-$id-incidents.csv") })
        }
    }
    message?.let { Text(it) }
}
