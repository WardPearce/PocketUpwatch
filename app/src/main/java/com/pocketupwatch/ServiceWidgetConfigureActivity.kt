package com.pocketupwatch

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServiceWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId <= AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val dao = MonitorDb.get(this).dao()
        val initial = ServiceWidgetSettings.serviceId(this, widgetId)
        setContent {
            val mode = remember { getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "System") ?: "System" }
            val services by dao.services().collectAsStateWithLifecycle(initialValue = null)
            var selected by rememberSaveable { mutableLongStateOf(initial ?: 0) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            BackHandler(busy) { }
            MaterialTheme(colorScheme = appColorScheme(mode)) {
                ThemedSystemBars(this)
                ServiceWidgetPicker(services, selected, initial != null, busy, error,
                    onSelect = { selected = it; error = null }, onCancel = { finish() },
                    onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                    onSave = {
                        val selectedServiceId = selected
                        busy = true
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ServiceWidget.configure(this@ServiceWidgetConfigureActivity, AppWidgetManager.getInstance(this@ServiceWidgetConfigureActivity), widgetId, selectedServiceId, dao)
                                }
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                error = "Could not save the widget. Check that the service still exists and try again."
                            } finally { busy = false }
                        }
                    })
            }
        }
    }
}

@Composable internal fun ServiceWidgetPicker(
    services: List<ServiceEntry>?, selectedId: Long, reconfiguring: Boolean, busy: Boolean, error: String?,
    onSelect: (Long) -> Unit, onCancel: () -> Unit, onOpenApp: () -> Unit, onSave: () -> Unit
) {
    Scaffold(topBar = { AppTopBar(if (reconfiguring) "Configure service widget" else "Add service widget", onBack = if (busy) null else onCancel) },
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppSecondaryButton(onCancel, Modifier.weight(1f), enabled = !busy) { Text("Cancel") }
                        AppButton(onSave, Modifier.weight(1f), enabled = !busy && services?.any { it.id == selectedId } == true) {
                            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (reconfiguring) "Save widget" else "Add widget")
                        }
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Choose a service to show its status, uptime, and availability graph for the last 24 hours. Each widget can track a different service.") }
            if (services == null) item { CircularProgressIndicator() }
            else if (services.isEmpty()) item {
                DetailSection {
                    Text("No services yet", style = MaterialTheme.typography.titleMedium)
                    Text("Add a service in Pocket Upwatch, then return here to select it.")
                    AppSecondaryButton(onOpenApp, enabled = !busy) { Text("Open Pocket Upwatch") }
                }
            }
            else items(services, key = { it.id }) { service ->
                Card(Modifier.fillMaxWidth().testTag("widget-service-${service.id}").selectable(
                    selected = service.id == selectedId, enabled = !busy, role = Role.RadioButton, onClick = { onSelect(service.id) }
                ), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = service.id == selectedId, onClick = null, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text(service.name, style = MaterialTheme.typography.titleMedium)
                            Text(displayTarget(service.url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
