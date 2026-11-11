package com.pocketupwatch

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun LibraryTools(vm: MonitorViewModel, mode: String, onMode: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var retention by remember { mutableIntStateOf(prefs.getInt("retention_days", 90)) }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<ServiceEntry>?>(null) }
    var preparedExport by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = preparedExport
        if (uri != null && data != null) scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(data) } }; message = "Backup exported" }
            catch (_: Exception) { message = "Could not write backup" }
            finally { busy = false; preparedExport = null; passphrase = "" }
        } else preparedExport = null
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val password = passphrase.toCharArray()
            try {
                preview = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 8 * 1_024 * 1_024); output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                    BackupCodec.decode(bytes.toString(Charsets.UTF_8), password, SecretStore()::encrypt)
                }
            } catch (_: Exception) { message = "Could not read backup. Check its format and passphrase." }
            finally { password.fill('\u0000'); passphrase = ""; busy = false }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceMenu("Theme", mode, listOf("System", "Light", "Dark"), onMode)
        ChoiceMenu("Keep raw history", "$retention days", listOf("30 days", "90 days", "180 days", "365 days")) { retention = it.substringBefore(' ').toInt(); prefs.edit().putInt("retention_days", retention).apply() }
        Text("Old raw checks are cleaned up during monitoring. Hourly summaries are kept for one year. Incidents remain until their monitor is deleted.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open battery and notification settings") }
        Text("Configuration backup", style = MaterialTheme.typography.titleLarge)
        Text("Without a passphrase, exports omit headers, request/expected bodies and URL query values. With a passphrase of at least 12 characters, the full configuration is encrypted. Imported monitors are always paused; review them before enabling.")
        TextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Backup passphrase (optional for export)") }, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && (passphrase.isEmpty() || passphrase.length >= 12), onClick = {
            busy = true
            scope.launch {
                val password = passphrase.toCharArray()
                try {
                    preparedExport = withContext(Dispatchers.IO) { BackupCodec.encode(vm.dao.allServices(), password, SecretStore()::decrypt) }
                    export.launch("PocketUpwatch-config.json")
                } catch (_: Exception) { message = "Could not prepare backup" }
                finally { password.fill('\u0000'); busy = false }
            }
        }) { Text("Export configuration") }
        OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import configuration") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
        Text("Android shortcuts", style = MaterialTheme.typography.titleLarge)
        Text("Add the Pocket Upwatch widget from your launcher's widget picker. Add the monitoring tile from Quick Settings to pause or resume all monitors.")
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
    preview?.let { monitors ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Import ${monitors.size} monitors?") }, text = { Text("These monitors will be added as paused copies. Existing monitors and history will be kept. Review redacted settings and credentials before enabling.") }, confirmButton = {
            TextButton(onClick = { scope.launch {
                try { vm.dao.importServices(monitors); message = "Imported ${monitors.size} paused monitors" }
                catch (_: Exception) { message = "Could not import monitors" }
                finally { preview = null }
            } }) { Text("Import paused copies") }
        }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } })
    }
}

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
                message = "CSV exported"
            } catch (_: Exception) { message = "Could not export CSV" }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { incidents = false; export.launch("PocketUpwatch-$id-checks.csv") }) { Text("Export checks") }
        OutlinedButton(onClick = { incidents = true; export.launch("PocketUpwatch-$id-incidents.csv") }) { Text("Export incidents") }
    }
    message?.let { Text(it) }
}
