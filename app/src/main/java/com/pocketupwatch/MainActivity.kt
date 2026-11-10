package com.pocketupwatch

import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import android.Manifest
import android.content.Intent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
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
import kotlin.math.roundToInt

@Composable private fun appColorScheme(mode: String): ColorScheme {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = when (mode) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    return if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }; var mode by remember { mutableStateOf(prefs.getString("theme", "System") ?: "System") }; MaterialTheme(colorScheme = appColorScheme(mode)) { App(mode = mode, onMode = { mode = it; prefs.edit().putString("theme", it).apply() }) } } }
}
@Composable private fun Onboarding(onComplete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationRequested = true; refresh++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val granted = remember(step, refresh) {
        when (step) {
            0 -> androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            1 -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            else -> CheckAlarm.canScheduleExact(context)
        }
    }
    val titles = listOf("Notifications", "Background monitoring", "Scheduled checks")
    val descriptions = listOf(
        "Allow notifications to see monitoring status and receive alerts when a service changes state.",
        "Allow unrestricted battery use to improve monitoring in the background. This may use more battery.",
        "Allow alarms and reminders to help schedule checks while your phone sleeps. Android may still delay checks."
    )
    val advance = { if (step < 2) step++ else onComplete() }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Set up Pocket Upwatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Step ${step + 1} of 3", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
            Text(titles[step], style = MaterialTheme.typography.headlineSmall)
            Text(descriptions[step], style = MaterialTheme.typography.bodyLarge)
            if (granted) Text("Already allowed", color = MaterialTheme.colorScheme.primary)
            if (!granted) {
                Button(onClick = {
                    when (step) {
                        0 -> if (Build.VERSION.SDK_INT >= 33 && !notificationRequested && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        1 -> settings.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                        2 -> if (Build.VERSION.SDK_INT >= 31) settings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
            }
            Button(onClick = advance, modifier = Modifier.fillMaxWidth()) { Text(if (step == 2) "Finish" else "Continue") }
            if (!granted) Text("You can continue without allowing this and change it later in settings.", style = MaterialTheme.typography.bodySmall)
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
        }
    }
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
    var notificationsEnabled by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryUnrestricted by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
    val batteryPrefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var exactAlarmsAllowed by remember { mutableStateOf(CheckAlarm.canScheduleExact(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exactAlarmsAllowed, monitoringActive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                val wasAllowed = exactAlarmsAllowed
                exactAlarmsAllowed = CheckAlarm.canScheduleExact(context)
                if (!wasAllowed && exactAlarmsAllowed && monitoringActive) {
                    androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(CheckAlarm.ACTION_CHECK))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(loadedServices != null) {
        if (loadedServices?.any { it.enabled } == true) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
    var onboardingComplete by remember { mutableStateOf(batteryPrefs.getBoolean("onboarding_complete", false)) }
    if (!onboardingComplete) {
        // Existing installations have already seen the old permission prompts.
        if (loadedServices == null) return
        if (services.isNotEmpty() || batteryPrefs.contains("battery_prompt_shown") || batteryPrefs.contains("alarm_prompt_shown")) {
            LaunchedEffect(Unit) {
                batteryPrefs.edit().putBoolean("onboarding_complete", true).apply()
                onboardingComplete = true
            }
        } else {
            Onboarding(onComplete = {
                batteryPrefs.edit().putBoolean("onboarding_complete", true).apply()
                onboardingComplete = true
            })
        }
        return
    }
    val showingEditor = adding || editing != null
    val closeEditor = { adding = false; editing = null }
    BackHandler(enabled = showingEditor || selected != null) { if (showingEditor) closeEditor() else selected = null }
    Scaffold(topBar = { TopAppBar(title = { Text(when { adding -> "Add a service"; editing != null -> "Edit service"; selected != null -> selected!!.name; else -> "Pocket Upwatch" }, fontWeight = FontWeight.Bold) }, navigationIcon = { if (showingEditor || selected != null) IconButton(onClick = { if (showingEditor) closeEditor() else selected = null }) { Icon(Icons.Default.ArrowBack, "Back") } }) }, floatingActionButton = { if (selected == null && !showingEditor) FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add service") } }) { padding ->
        if (showingEditor) ServiceEditor(editing, Modifier.padding(padding), onCancel = closeEditor, onSave = { service ->
            vm.save(service); closeEditor(); selected = if (service.id != 0L) service else null
            if (service.enabled) { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        })
        else if (selected != null) Detail(selected!!, vm, Modifier.padding(padding), onEdit = { editing = it }) { selected = null }
        else LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (monitoringActive) "Monitoring service active" else "Monitoring stopped · results may be out of date")
                    
                    Text("Checks reflect this device and network. Gaps are unknown; continuous coverage is not guaranteed.", style = MaterialTheme.typography.bodySmall)
                    if (!notificationsEnabled) {
                        Text("Notifications are disabled. Enable them in your phone's app settings to receive alerts.", color = MaterialTheme.colorScheme.error)
                    }
                    if (!exactAlarmsAllowed && Build.VERSION.SDK_INT >= 31) {
                        Text("Allow alarms and reminders for more timely checks while the phone sleeps.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("Open alarm settings") }
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
                if (enabled) { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
            }) { selected = service } }
        }
    }

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
                    Text(if (latest == null) "Every ${formatInterval(service.intervalSeconds)}" else "Last check ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(latest.time))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = service.enabled, onCheckedChange = onToggle)
            }
        }
    }
}
@Composable fun ResponseChart(history: List<CheckEntry>, modifier: Modifier = Modifier) {
    val points = remember(history) { history.sortedBy { it.time } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (points.isEmpty()) { Text("No checks in this period"); return@Column }
        if (points.size == 1) {
            Text("${points.first().responseMs} ms · ${DateFormat.getDateTimeInstance().format(Date(points.first().time))}")
            return@Column
        }
        val entries = remember(points) { points.map { entryOf(((it.time - points.first().time) / 1000L).toFloat(), it.responseMs.toFloat()) }.distinctBy { it.x } }
        if (entries.size < 2) { Text("${points.last().responseMs} ms"); return@Column }
        ProvideChartStyle(m3ChartStyle()) {
            Chart(chart = lineChart(), model = remember(entries) { entryModelOf(entries) },
                modifier = Modifier.fillMaxWidth().weight(1f), startAxis = rememberStartAxis(),
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(points.first().time)), style = MaterialTheme.typography.labelSmall)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(points.last().time)), style = MaterialTheme.typography.labelSmall)
        }
        Text("${points.size} checks · response time in ms", style = MaterialTheme.typography.bodySmall)
    }
}
class GraphActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("serviceId", 0L)
        val hours = intent.getIntExtra("hours", 24)
        val graph = intent.getStringExtra("graph") ?: "Response time"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val history by remember(id) { MonitorDb.get(application).dao().history(id) }.collectAsState(initial = emptyList())
            val filtered = history.filter { it.time >= System.currentTimeMillis() - hours * 3600_000L }
            val mode = remember { getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "System") ?: "System" }
            MaterialTheme(colorScheme = appColorScheme(mode)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeContent).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("$graph · ${hours}h", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close fullscreen") }
                        }
                        if (graph == "Response time") ResponseChart(filtered, Modifier.fillMaxWidth().weight(1f))
                        else if (filtered.isEmpty()) Text("No checks in this period")
                        else {
                            AvailabilityGraph(filtered, hours, Modifier.fillMaxWidth().weight(1f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${hours}h ago"); Text("Now") }
                            Text("Green: up · Red: down · Blank: no checks", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
@Composable fun Detail(service: ServiceEntry, vm: MonitorViewModel, modifier: Modifier, onEdit: (ServiceEntry) -> Unit, onDeleted: () -> Unit) {
    val history by remember(service.id) { vm.dao.history(service.id) }.collectAsState(initial = emptyList())
    val active by MonitorService.active.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var hours by remember { mutableIntStateOf(24) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val openFullscreen: (String) -> Unit = { graph ->
        context.startActivity(Intent(context, GraphActivity::class.java).putExtra("serviceId", service.id).putExtra("hours", hours).putExtra("graph", graph))
    }
    val filtered = history.filter { it.time >= System.currentTimeMillis() - hours * 3600_000L }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val latest = history.firstOrNull()
                Text(if (!service.enabled) "PAUSED" else if (!active) "STOPPED" else if (latest == null) "PENDING" else if (System.currentTimeMillis() - latest.time > maxOf(service.intervalSeconds * 2_000L, 120_000L)) "STALE" else if (latest.up) "UP" else "DOWN", style = MaterialTheme.typography.labelLarge, color = if (latest?.up == true && service.enabled) Color(0xff16835c) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text(if (latest == null) "No checks yet" else "${latest.responseMs} ms", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(service.url, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("Every ${formatInterval(service.intervalSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onEdit(service) }) { Text("Edit service") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(1 to "1h", 24 to "24h", 168 to "7d", 720 to "30d").forEach { (h, label) -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) }) } }
        DetailSection(MaterialTheme.colorScheme.primary) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Availability", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { openFullscreen("Availability") }) { Icon(Icons.Default.Fullscreen, "Fullscreen availability") }
            }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Response time", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { openFullscreen("Response time") }) { Icon(Icons.Default.Fullscreen, "Fullscreen response time") }
            }
            ResponseChart(filtered, Modifier.fillMaxWidth().height(220.dp))
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
private fun formatInterval(seconds: Int): String {
    val parts = listOf(3600 to "hour", 60 to "minute", 1 to "second")
    var remaining = seconds
    return parts.mapNotNull { (unit, label) ->
        val count = remaining / unit
        remaining %= unit
        if (count == 0) null else "$count $label${if (count == 1) "" else "s"}"
    }.joinToString(" ")
}

@Composable fun ServiceEditor(existing: ServiceEntry?, modifier: Modifier = Modifier, onCancel: () -> Unit, onSave: (ServiceEntry) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }; var url by remember { mutableStateOf(existing?.url ?: "") }
    var interval by remember { mutableIntStateOf(existing?.intervalSeconds?.coerceAtLeast(300) ?: 300) }
    val intervals = remember(existing?.intervalSeconds) {
        (listOf(300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400) + listOfNotNull(existing?.intervalSeconds?.takeIf { it in 300..86400 })).distinct().sorted()
    }
    var code by remember { mutableStateOf(existing?.expectedCode?.takeIf { it != 0 }?.toString() ?: "200") }
    var type by remember { mutableStateOf(existing?.bodyType ?: "None") }; var body by remember { mutableStateOf(existing?.expectedBody ?: "") }
    val status = code.toIntOrNull()
    val valid = name.isNotBlank() && url.trim().toHttpUrlOrNull() != null && interval in 300..86400 && (status in 100..599) && (type == "None" || body.isNotBlank())
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        TextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTP or HTTPS URL") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Text("Check interval: ${formatInterval(interval)}", style = MaterialTheme.typography.titleSmall)
        Slider(value = intervals.indexOf(interval).coerceAtLeast(0).toFloat(), onValueChange = { interval = intervals[it.roundToInt().coerceIn(intervals.indices)] }, valueRange = 0f..intervals.lastIndex.toFloat(), steps = intervals.size - 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("5 minutes"); Text("24 hours") }
        if (interval < 900) Text("Frequent checks can increase battery drain, especially while your phone sleeps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Status code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Expected response body")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("None", "Text", "JSON", "XML").forEach { choice -> FilterChip(selected = type == choice, onClick = { type = choice }, label = { Text(choice) }) } }
        if (type != "None") {
            val syntaxColor = MaterialTheme.colorScheme.primary
            val syntax = remember(type, syntaxColor) {
                VisualTransformation { input ->
                    val styled = AnnotatedString.Builder(input)
                    val pattern = when (type) {
                        "JSON" -> Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)|\\b(?:true|false|null)\\b")
                        "XML" -> Regex("</?[A-Za-z_][A-Za-z0-9_.:-]*|/?>")
                        else -> null
                    }
                    pattern?.findAll(input.text)?.forEach { match ->
                        styled.addStyle(SpanStyle(color = syntaxColor, fontWeight = FontWeight.SemiBold), match.range.first, match.range.last + 1)
                    }
                    TransformedText(styled.toAnnotatedString(), OffsetMapping.Identity)
                }
            }
            TextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Expected $type body") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 6, maxLines = 14, keyboardOptions = KeyboardOptions(autoCorrect = false),
                visualTransformation = syntax)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = valid, onClick = { onSave(ServiceEntry(id = existing?.id ?: 0, name = name.trim(), url = url.trim(), intervalSeconds = interval, enabled = existing?.enabled ?: true, expectedCode = status ?: 200, bodyType = type, expectedBody = body)) }) { Text("Save") }
        }
    }
}
