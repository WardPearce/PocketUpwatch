package com.pocketupwatch

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }; var mode by remember { mutableStateOf(prefs.getString("theme", "System") ?: "System") }; val dark = when (mode) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }; val colors = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this) } else { if (dark) darkColorScheme() else lightColorScheme() }; MaterialTheme(colorScheme = colors) { App(mode = mode, onMode = { mode = it; prefs.edit().putString("theme", it).apply() }) } } }
}
class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    val dao = MonitorDb.get(app).dao()
    val services = dao.services()
    fun save(service: ServiceEntry) = viewModelScope.launch { if (service.id == 0L) dao.add(service) else dao.update(service) }
    fun delete(service: ServiceEntry) = viewModelScope.launch { dao.delete(service) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(mode: String, onMode: (String) -> Unit, vm: MonitorViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loadedServices by vm.services.collectAsState(initial = null)
    val services = loadedServices.orEmpty()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServiceEntry?>(null) }
    var selected by remember { mutableStateOf<ServiceEntry?>(null) }
    val monitoringActive by MonitorService.active.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var notificationsEnabled by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryUnrestricted by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
    val batteryPrefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var showBatteryPrompt by remember { mutableStateOf(!batteryUnrestricted && !batteryPrefs.getBoolean("battery_prompt_shown", false)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(loadedServices != null) {
        if (loadedServices?.any { it.enabled } == true) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (showBatteryPrompt) {
        AlertDialog(
            onDismissRequest = { showBatteryPrompt = false; batteryPrefs.edit().putBoolean("battery_prompt_shown", true).apply() },
            title = { Text("Allow background monitoring") },
            text = { Text("Allow unrestricted battery use to improve monitoring while the app is in the background. This may use more battery, and uninterrupted checks cannot be guaranteed.") },
            confirmButton = { TextButton(onClick = {
                showBatteryPrompt = false
                batteryPrefs.edit().putBoolean("battery_prompt_shown", true).apply()
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { showBatteryPrompt = false; batteryPrefs.edit().putBoolean("battery_prompt_shown", true).apply() }) { Text("Not now") } }
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text(if (selected == null) "Pocket Upwatch" else selected!!.name, fontWeight = FontWeight.Bold) }, navigationIcon = { if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Back") } }) }, floatingActionButton = { if (selected == null) FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add service") } }) { padding ->
        if (selected != null) Detail(selected!!, vm, Modifier.padding(padding), onEdit = { editing = it }) { selected = null }
        else LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (monitoringActive) "Monitoring service active" else "Monitoring stopped · results may be out of date")
                    
                    Text("Checks reflect this device and network. Gaps are unknown; continuous coverage is not guaranteed.", style = MaterialTheme.typography.bodySmall)
                    if (!notificationsEnabled) {
                        Text("Notifications are disabled. Enable them to receive alerts.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
                    }
                    if (!batteryUnrestricted && services.any { it.enabled }) {
                        Text("Unrestricted battery use can improve background monitoring, but may use more power.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app battery settings") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Services", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${services.size} total · ${services.count { it.enabled }} enabled", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (services.isEmpty()) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp)) { Text("No services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text("Add an endpoint to start monitoring.", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            items(services, key = { it.id }) { service -> ServiceCard(service, vm, onToggle = { enabled ->
                vm.save(service.copy(enabled = enabled))
                if (enabled) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS); androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
            }) { selected = service } }
        }
    }
    if (adding || editing != null) AddDialog(editing, onDismiss = { adding = false; editing = null }, onSave = { service ->
        vm.save(service); adding = false; editing = null; selected = if (service.id != 0L) service else null
        if (service.enabled) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS); androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
    })
}
@Composable fun ServiceCard(service: ServiceEntry, vm: MonitorViewModel, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val history by remember(service.id) { vm.dao.history(service.id) }.collectAsState(initial = emptyList())
    val latest = history.firstOrNull()
    val active by MonitorService.active.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10_000); now = System.currentTimeMillis() } }
    val stale = latest != null && now - latest.time > maxOf(service.intervalSeconds * 2_000L, 120_000L)
    val status = if (!service.enabled) "Paused" else if (!active) "Stopped" else if (latest == null) "Pending" else if (stale) "Stale" else if (latest.up) "Up" else "Down"
    val statusColor = if (!service.enabled || !active || stale || latest == null) MaterialTheme.colorScheme.onSurfaceVariant else if (latest.up) Color(0xff16835c) else MaterialTheme.colorScheme.error
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(service.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(service.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Surface(color = statusColor.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                    Text(status.uppercase(), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = statusColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (latest == null) "No checks yet" else "${latest.responseMs} ms · ${latest.detail}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (latest == null) "Every ${service.intervalSeconds}s" else "Last check ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(latest.time))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = service.enabled, onCheckedChange = onToggle)
            }
        }
    }
}
@Composable fun Graph(history: List<CheckEntry>, modifier: Modifier = Modifier) {
    val points = history.take(60).asReversed()
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val max = points.maxOfOrNull { it.responseMs }?.coerceAtLeast(1) ?: 1
        points.zipWithNext().forEachIndexed { i, pair ->
            val x1 = size.width * i / (points.size - 1); val x2 = size.width * (i + 1) / (points.size - 1)
            drawLine(color, Offset(x1, size.height * (1f - pair.first.responseMs.toFloat() / max)), Offset(x2, size.height * (1f - pair.second.responseMs.toFloat() / max)), strokeWidth = 4f)
        }
    }
}
@Composable fun Detail(service: ServiceEntry, vm: MonitorViewModel, modifier: Modifier, onEdit: (ServiceEntry) -> Unit, onDeleted: () -> Unit) {
    val history by remember(service.id) { vm.dao.history(service.id) }.collectAsState(initial = emptyList())
    val active by MonitorService.active.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var hours by remember { mutableIntStateOf(24) }
    val filtered = history.filter { it.time >= System.currentTimeMillis() - hours * 3600_000L }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val latest = history.firstOrNull()
                Text(if (!service.enabled) "PAUSED" else if (!active) "STOPPED" else if (latest == null) "PENDING" else if (System.currentTimeMillis() - latest.time > maxOf(service.intervalSeconds * 2_000L, 120_000L)) "STALE" else if (latest.up) "UP" else "DOWN", style = MaterialTheme.typography.labelLarge, color = if (latest?.up == true && service.enabled) Color(0xff16835c) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text(if (latest == null) "No checks yet" else "${latest.responseMs} ms", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(service.url, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("Every ${service.intervalSeconds}s", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onEdit(service) }) { Text("Edit service") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(1 to "1h", 24 to "24h", 168 to "7d", 720 to "30d").forEach { (h, label) -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) }) } }
        DetailSection(MaterialTheme.colorScheme.primary) {
            Text("Availability", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val up = filtered.count { it.up }
            Text(if (filtered.isEmpty()) "No checks in this period" else "${"%.1f".format(100.0 * up / filtered.size)}% of samples successful · $up up / ${filtered.size - up} down", style = MaterialTheme.typography.titleMedium)
            if (filtered.isNotEmpty()) {
                AvailabilityGraph(filtered, hours, Modifier.fillMaxWidth().height(45.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${hours}h ago", style = MaterialTheme.typography.labelSmall)
                    Text("Now", style = MaterialTheme.typography.labelSmall)
                }
                Text("Green: up · Red: down · Blank: no checks", style = MaterialTheme.typography.bodySmall)
            }
            Text("Based on up to 500 recent checks. Gaps are unknown.", style = MaterialTheme.typography.bodySmall)
        }
        DetailSection(MaterialTheme.colorScheme.secondary) {
            Text("Response time", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (filtered.size > 1) {
                val points = filtered.take(60).asReversed()
                val max = points.maxOf { it.responseMs }.coerceAtLeast(1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("$max ms", style = MaterialTheme.typography.labelSmall)
                        Text("0 ms", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Graph(filtered, Modifier.weight(1f).height(150.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(points.first().time)), style = MaterialTheme.typography.labelSmall)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(points.last().time)), style = MaterialTheme.typography.labelSmall)
                }
                Text("Last ${points.size} checks · evenly spaced", style = MaterialTheme.typography.bodySmall)
            } else Text("At least two checks are needed.")
        }
        DetailSection(MaterialTheme.colorScheme.outline) {
            Text("Recent checks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (history.isEmpty()) Text("No checks yet.")
            history.take(50).forEach { check -> Text("${if (check.up) "UP" else "DOWN"}  ·  ${check.responseMs} ms  ·  ${check.detail}\n${DateFormat.getDateTimeInstance().format(Date(check.time))}", style = MaterialTheme.typography.bodyMedium); HorizontalDivider() }
        }
        OutlinedButton(onClick = { confirm = true }) { Text("Delete service") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete ${service.name}?") }, text = { Text("Its check history will also be deleted.") }, confirmButton = { TextButton(onClick = { vm.delete(service); onDeleted() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
@Composable fun DetailSection(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(3.dp).then(Modifier.background(color.copy(alpha = 0.65f))))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}
@Composable fun AvailabilityGraph(history: List<CheckEntry>, hours: Int, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis(); val start = now - hours * 3600_000L
    Canvas(modifier) {
        history.forEach { check ->
            val x = ((check.time - start).toFloat() / (now - start)).coerceIn(0f, 1f) * size.width
            drawLine(if (check.up) Color(0xff16835c) else Color(0xffc62828), Offset(x, 0f), Offset(x, size.height), strokeWidth = 5f)
        }
    }
}
@Composable fun AddDialog(existing: ServiceEntry?, onDismiss: () -> Unit, onSave: (ServiceEntry) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }; var url by remember { mutableStateOf(existing?.url ?: "") }; var seconds by remember { mutableStateOf(existing?.intervalSeconds?.toString() ?: "60") }
    var code by remember { mutableStateOf(existing?.expectedCode?.takeIf { it != 0 }?.toString() ?: "") }
    var type by remember { mutableStateOf(existing?.bodyType ?: "None") }; var body by remember { mutableStateOf(existing?.expectedBody ?: "") }
    val interval = seconds.toIntOrNull(); val status = code.toIntOrNull()
    val valid = name.isNotBlank() && url.toHttpUrlOrNull() != null && interval != null && interval in 30..86400 && (code.isBlank() || status in 100..599) && (type == "None" || body.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add a service" else "Edit service") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(url, { url = it }, label = { Text("HTTP or HTTPS URL") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(seconds, { seconds = it }, label = { Text("Interval in seconds (30–86400)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(code, { code = it }, label = { Text("Expected HTTP code (blank: any 2xx)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Expected response body")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("None", "Text", "JSON", "XML").forEach { choice -> FilterChip(selected = type == choice, onClick = { type = choice }, label = { Text(choice) }) } }
        if (type != "None") OutlinedTextField(body, { body = it }, label = { Text("Expected $type body") })
    } }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(ServiceEntry(id = existing?.id ?: 0, name = name.trim(), url = url.trim(), intervalSeconds = interval!!, enabled = existing?.enabled ?: true, expectedCode = status ?: 0, bodyType = type, expectedBody = body)) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
