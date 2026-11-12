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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.compose.foundation.text.selection.SelectionContainer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable internal fun appColorScheme(mode: String): ColorScheme {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = when (mode) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    return if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
}

@Composable internal fun ThemedSystemBars(activity: ComponentActivity) {
    val surface = MaterialTheme.colorScheme.surface
    SideEffect {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = surface.toArgb()
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = surface.toArgb()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = surface.luminance() > 0.5f
            isAppearanceLightNavigationBars = surface.luminance() > 0.5f
        }
    }
}

class MainActivity : ComponentActivity() {
    val requestedServiceId = mutableLongStateOf(0)
    val navigationRequest = mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); requestedServiceId.longValue = intent.getLongExtra("serviceId", 0L); navigationRequest.intValue++ }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); requestedServiceId.longValue = intent.getLongExtra("serviceId", 0L); setContent { val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }; var mode by remember { mutableStateOf(prefs.getString("theme", "System") ?: "System") }; MaterialTheme(colorScheme = appColorScheme(mode)) { ThemedSystemBars(this); App(requestedServiceId = requestedServiceId.longValue, navigationRequest = navigationRequest.intValue, mode = mode, onMode = { mode = it; prefs.edit().putString("theme", it).apply() }) } } }
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
    val advance: () -> Unit = { if (step < 2) step++ else onComplete() }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Set up Pocket Upwatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Step ${step + 1} of 3", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
            Text(titles[step], style = MaterialTheme.typography.headlineSmall)
            Text(descriptions[step], style = MaterialTheme.typography.bodyLarge)
            if (granted) Text("Already allowed", color = MaterialTheme.colorScheme.primary)
            if (!granted) {
                AppButton(onClick = {
                    when (step) {
                        0 -> if (Build.VERSION.SDK_INT >= 33 && !notificationRequested && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        1 -> settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        2 -> if (Build.VERSION.SDK_INT >= 31) settings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
            }
            if (!granted) Text("You can continue without allowing this and change it later in settings.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) AppSecondaryButton(onClick = { step-- }, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Back") }
                AppButton(onClick = advance, modifier = Modifier.weight(1f).fillMaxHeight()) { Text(if (step == 2) "Finish" else "Continue") }
            }
        }
    }
}
class MonitorViewModel @JvmOverloads constructor(app: Application, val dao: MonitorDao = MonitorDb.get(app).dao()) : AndroidViewModel(app) {
    val services = dao.services()
    val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun save(service: ServiceEntry, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        try {
            val id = if (service.id == 0L) dao.add(service) else { dao.update(service); service.id }
            if (service.enabled) MonitorService.start(getApplication())
            MonitoringWidget.updateAll(getApplication(), id)
            onSaved(id)
        } catch (_: Exception) { error.value = "Could not save the monitor" }
    }
    fun delete(service: ServiceEntry, done: () -> Unit) = viewModelScope.launch {
        try { dao.delete(service); MonitoringWidget.updateAll(getApplication(), service.id); done() } catch (_: Exception) { error.value = "Could not delete the monitor" }
    }
    fun setAll(enabled: Boolean) = viewModelScope.launch {
        try {
            dao.setAllEnabled(enabled)
            if (enabled) MonitorService.start(getApplication())
            MonitoringWidget.updateAll(getApplication())
        } catch (_: Exception) { error.value = "Could not change monitoring state" }
    }
}

@Composable internal fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) }
        }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(requestedServiceId: Long, navigationRequest: Int, mode: String, onMode: (String) -> Unit, vm: MonitorViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loaded by vm.services.collectAsStateWithLifecycle(initialValue = null)
    val services = loaded.orEmpty()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableLongStateOf(0) }
    var selectedId by rememberSaveable { mutableLongStateOf(0) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("Newest") }
    val active by MonitorService.active.collectAsStateWithLifecycle()
    val now = rememberNow()
    val latestChecks by remember { vm.dao.latestChecks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val latestById = latestChecks.associateBy { it.serviceId }
    val error by vm.error.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var onboardingComplete by rememberSaveable { mutableStateOf(prefs.getBoolean("onboarding_complete", false)) }
    LaunchedEffect(requestedServiceId, navigationRequest) { if (requestedServiceId != 0L) selectedId = requestedServiceId }
    LaunchedEffect(loaded != null) {
        if (loaded?.isNotEmpty() == true) { prefs.edit().putBoolean("onboarding_complete", true).apply(); onboardingComplete = true }
        if (loaded?.any { it.enabled } == true) MonitorService.start(context)
    }
    if (loaded == null) return
    if (!onboardingComplete && services.isEmpty()) {
        Onboarding { prefs.edit().putBoolean("onboarding_complete", true).apply(); onboardingComplete = true }
        return
    }
    val selected = services.firstOrNull { it.id == selectedId }
    val editing = services.firstOrNull { it.id == editingId }
    val editor = adding || editing != null
    val canNavigateBack = editor || selected != null || tools
    BackHandler(canNavigateBack) {
        when { editor -> { adding = false; editingId = 0 }; tools -> tools = false; else -> selectedId = 0 }
    }
    Scaffold(topBar = {
        AppTopBar(title = when { editor -> if (adding) "Add monitor" else "Edit monitor"; tools -> "Backup and settings"; selected != null -> selected.name; else -> "Pocket Upwatch" },
            onBack = if (canNavigateBack) ({ when { editor -> { adding = false; editingId = 0 }; tools -> tools = false; else -> selectedId = 0 } }) else null,
            actions = { if (!canNavigateBack) AppIconButton(onClick = { tools = true }, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Default.Settings, "Backup and settings") } })
    }, floatingActionButton = {
        if (!canNavigateBack) ExtendedFloatingActionButton(onClick = { adding = true }, shape = RoundedCornerShape(8.dp), icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add monitor") })
    }) { padding ->
        when {
            editor -> ServiceEditor(editing, Modifier.padding(padding), onCancel = { adding = false; editingId = 0 }) { service -> vm.save(service) { id -> adding = false; editingId = 0; selectedId = id }.join() }
            tools -> LibraryTools(vm, mode, onMode, Modifier.padding(padding))
            selected != null -> Detail(selected, vm, Modifier.padding(padding), onEdit = { editingId = it.id }, onDeleted = { selectedId = 0 })
            else -> {
                val visible = services.filter { "${it.name} ${displayTarget(it.url)} ${it.tags}".contains(search, true) }.let { matches -> when (sort) { "Name" -> matches.sortedBy { it.name.lowercase() }; "Paused first" -> matches.sortedBy { it.enabled }; "Tag" -> matches.sortedBy { it.tags.lowercase() }; "Status" -> matches.sortedWith(compareBy<ServiceEntry> { when (MonitorStatus.label(it, latestById[it.id], active, now)) { "DOWN" -> 0; "STALE" -> 1; "UNKNOWN" -> 2; "STOPPED" -> 3; "PENDING" -> 4; "UP" -> 5; else -> 6 } }.thenBy { it.name.lowercase() }); else -> matches } }
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (active) "${services.count { it.enabled }} enabled monitors" else "Monitoring stopped")
                            Text("Results reflect this device and network. Unknown gaps are excluded from success rates.", style = MaterialTheme.typography.bodySmall)
                            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) Text("Notifications are disabled. Enable them in app settings to receive alerts.", color = MaterialTheme.colorScheme.error)
                            if (!CheckAlarm.canScheduleExact(context)) AppSecondaryButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("Allow timely alarms") }
                            TextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search names, targets, or tags") }, singleLine = true)
                            ChoiceMenu("Sort", sort, listOf("Newest", "Name", "Status", "Paused first", "Tag")) { sort = it }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppSecondaryButton(onClick = { vm.setAll(false) }, modifier = Modifier.weight(1f), enabled = services.any { it.enabled }) { Text("Pause all") }
                                AppSecondaryButton(onClick = { vm.setAll(true) }, modifier = Modifier.weight(1f), enabled = services.any { !it.enabled }) { Text("Resume all") }
                            }
                        }
                    }
                    if (visible.isEmpty()) item { Text(if (services.isEmpty()) "Add your first monitor to start checking." else "No matching monitors") }
                    items(visible, key = { it.id }) { service -> ServiceCard(service, vm, onToggle = { vm.save(service.copy(enabled = it)) }) { selectedId = service.id } }
                }
            }
        }
    }
    if (error != null) AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("Could not complete action") }, text = { Text(error!!) }, confirmButton = { AppSecondaryButton(onClick = { vm.error.value = null }) { Text("OK") } })
}

@Composable fun ServiceCard(service: ServiceEntry, vm: MonitorViewModel, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val latest by remember(service.id) { vm.dao.latestFlow(service.id) }.collectAsStateWithLifecycle(initialValue = null)
    val active by MonitorService.active.collectAsStateWithLifecycle()
    val now = rememberNow()
    val status = MonitorStatus.label(service, latest, active, now)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(service.name, style = MaterialTheme.typography.titleMedium)
                    Text(displayTarget(service.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                StatusBadge(status)
                Switch(service.enabled, onToggle)
            }
            if (service.tags.isNotBlank()) Text(service.tags, style = MaterialTheme.typography.labelSmall)
            Text(latest?.let { if (it.skipped) it.detail else "${it.responseMs} ms · ${it.detail}" } ?: "No checks yet")
            Text(latest?.let { "Last attempt ${formatTime(it.time)}" } ?: "Every ${formatInterval(service.intervalSeconds)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun ChoiceMenu(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        AppSecondaryButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $value", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) } }
    }
}

@Composable fun Detail(service: ServiceEntry, vm: MonitorViewModel, modifier: Modifier, onEdit: (ServiceEntry) -> Unit, onDeleted: () -> Unit) {
    val latest by remember(service.id) { vm.dao.latestFlow(service.id) }.collectAsStateWithLifecycle(initialValue = null)
    val active by MonitorService.active.collectAsStateWithLifecycle()
    val incidents by remember(service.id) { vm.dao.incidents(service.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val now = rememberNow()
    val summaries by remember(service.id, now / 3_600_000) { vm.dao.rollups(service.id, now - 365 * 86_400_000L) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var hours by rememberSaveable(service.id) { mutableIntStateOf(24) }
    var confirm by remember { mutableStateOf(false) }
    var checking by remember(service.id) { mutableStateOf(false) }
    val runningChecks by MonitorService.checksInProgress.collectAsStateWithLifecycle()
    val isChecking = checking || service.id in runningChecks
    var checkError by remember(service.id) { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<ProbeResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailSection(Modifier.testTag("service-status")) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StatusBadge(MonitorStatus.label(service, latest, active, now))
                latest?.takeUnless { it.skipped }?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Response time", style = MaterialTheme.typography.labelSmall)
                        Text("${it.responseMs} ms", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            latest?.let { Text(it.detail, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        DetailSection(Modifier.testTag("service-actions")) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSecondaryButton(onClick = { onEdit(service) }, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Edit") }
                CheckNowButton(checking = isChecking, modifier = Modifier.weight(1f).fillMaxHeight(), onClick = {
                    checkError = null
                    if (service.enabled) {
                        try { MonitorService.start(context, service.id) }
                        catch (_: Exception) { checkError = "Could not start the check. Try again." }
                    } else {
                        checking = true
                        scope.launch {
                            try { diagnostic = withContext(Dispatchers.IO) { ProbeEngine.runner(context).run(service) } }
                            finally { checking = false }
                        }
                    }
                })
            }
            checkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!service.enabled) Text("A manual test on a paused monitor does not record history or send alerts.", style = MaterialTheme.typography.bodySmall)
            diagnostic?.let { Text("${if (it.up == null) "UNKNOWN" else if (it.up) "UP" else "DOWN"} · ${it.responseMs} ms · ${it.detail}") }
        }
        DetailSection(Modifier.testTag("service-target")) {
            Text("Target", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(displayTarget(service.url), style = MaterialTheme.typography.bodyLarge) }
        }
        DetailSection(Modifier.testTag("service-schedule")) {
            Text("Check schedule", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Interval", style = MaterialTheme.typography.labelSmall)
                    Text(formatInterval(service.intervalSeconds), style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text("Timeout", style = MaterialTheme.typography.labelSmall)
                    Text("${service.timeoutSeconds}s", style = MaterialTheme.typography.titleMedium)
                }
            }
            HorizontalDivider()
            Text("Next check", style = MaterialTheme.typography.labelMedium)
            val nextCheck = latest?.time?.plus(service.intervalSeconds * 1_000L)
            Text(when {
                !service.enabled -> "Paused"
                service.maintenanceUntil > now -> "Maintenance until ${formatTime(service.maintenanceUntil)}"
                isChecking -> "Check in progress"
                nextCheck == null -> "Waiting for first check"
                nextCheck <= now -> "Due now"
                else -> formatTime(nextCheck)
            })
            Text("Last attempt", style = MaterialTheme.typography.labelMedium)
            Text(latest?.let { formatTime(it.time) } ?: "No checks yet")
            Text("Android may delay scheduled checks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1 to "1h", 24 to "24h", 168 to "7d", 720 to "30d").forEach { (value, label) -> AppPeriodButton(label, hours == value, Modifier.weight(1f)) { hours = value } } }
        PeriodGraphs(service.id, hours, vm.dao, now) { graph -> context.startActivity(Intent(context, GraphActivity::class.java).putExtra("serviceId", service.id).putExtra("hours", hours).putExtra("graph", graph)) }
        DetailSection {
            Text("Incident history (latest 100)", style = MaterialTheme.typography.titleLarge)
            Text("${service.failureThreshold} failed checks to confirm down · ${service.recoveryThreshold} successful checks to recover", style = MaterialTheme.typography.bodySmall)
            if (incidents.isEmpty()) Text("No confirmed incidents")
            incidents.forEach { incident ->
                Text("${formatTime(incident.startedAt)} → ${incident.endedAt?.let(::formatTime) ?: "Ongoing"}\n${incident.reason}\n${incident.downChecks} failed checks · ${incident.unknownChecks} unknown checks", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
            }
        }
        DetailSection {
            Text("Historical summaries", style = MaterialTheme.typography.titleLarge)
            Text("Past year: ${summaries.sumOf { it.upCount }} up · ${summaries.sumOf { it.downCount }} down · ${summaries.sumOf { it.skippedCount }} skipped samples")
            Text("Hourly counts remain after raw-history cleanup; they describe observed samples and do not fill unknown gaps.", style = MaterialTheme.typography.bodySmall)
        }
        RecentChecks(service.id, vm.dao)
        DetailSection {
            Text("Manage monitor", style = MaterialTheme.typography.titleLarge)
            CsvExportButtons(service.id, vm.dao)
            HorizontalDivider()
            AppDangerButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete monitor") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete ${service.name}?") }, text = { Text("Its checks, summaries and incidents will also be deleted.") }, confirmButton = { AppDangerButton(onClick = { vm.delete(service, onDeleted) }) { Text("Delete") } }, dismissButton = { AppSecondaryButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable private fun PeriodGraphs(id: Long, hours: Int, dao: MonitorDao, now: Long, fullscreen: (String) -> Unit) {
    // Refresh rolling windows once per minute, not only when a result is written.
    val since = now / 60_000 * 60_000 - hours * 3_600_000L
    val stats by remember(id, since) { dao.stats(id, since) }.collectAsStateWithLifecycle(initialValue = PeriodStats())
    val buckets by remember(id, since, hours) { dao.buckets(id, since, maxOf(60_000L, hours * 3_600_000L / 240)) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val total = stats.upCount + stats.downCount
    DetailSection {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Availability", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); AppIconButton(onClick = { fullscreen("Availability") }) { Icon(Icons.Default.Fullscreen, "Fullscreen availability") } }
        Text(if (total == 0L) "No observed checks" else "${"%.1f".format(stats.upCount * 100.0 / total)}% of observed samples successful")
        Text("${stats.upCount} up · ${stats.downCount} down · ${stats.skippedCount} skipped. Missing checks are unknown.", style = MaterialTheme.typography.bodySmall)
        BucketAvailability(buckets, since, now, Modifier.fillMaxWidth().height(60.dp))
        Text("Green: up · red: down · gray: skipped · blank: no samples", style = MaterialTheme.typography.bodySmall)
        Text("${hours}h window · older raw checks may have been removed by your retention setting", style = MaterialTheme.typography.bodySmall)
    }
    DetailSection {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Response time", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); AppIconButton(onClick = { fullscreen("Response time") }) { Icon(Icons.Default.Fullscreen, "Fullscreen response time") } }
        ResponseChart(buckets.filter { it.upCount + it.downCount > 0 }.mapIndexed { index, b -> CheckEntry(index.toLong(), id, b.time, b.upCount > 0, b.responseMs, "") }, Modifier.fillMaxWidth().height(220.dp))
        Text("Mean probe duration per time bucket; concurrency queue time is excluded.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun BucketAvailability(buckets: List<ChartBucket>, since: Long, now: Long, modifier: Modifier) {
    val summary = "Availability graph: ${buckets.sumOf { it.upCount }} up, ${buckets.sumOf { it.downCount }} down, ${buckets.sumOf { it.skippedCount }} skipped samples"
    Canvas(modifier.semantics { contentDescription = summary }) {
        buckets.forEach { b ->
            val x = ((b.time - since).toFloat() / (now - since).coerceAtLeast(1)).coerceIn(0f, 1f) * size.width
            val total = (b.upCount + b.downCount + b.skippedCount).coerceAtLeast(1)
            val upHeight = size.height * b.upCount / total
            val downHeight = size.height * b.downCount / total
            drawLine(Color(0xff16835c), Offset(x, 0f), Offset(x, upHeight), strokeWidth = 4f)
            drawLine(Color(0xffc62828), Offset(x, upHeight), Offset(x, upHeight + downHeight), strokeWidth = 4f)
            drawLine(Color.Gray, Offset(x, upHeight + downHeight), Offset(x, size.height), strokeWidth = 4f)
        }
    }
}

class GraphActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("serviceId", 0)
        val hours = intent.getIntExtra("hours", 24).coerceIn(1, 720)
        val graph = intent.getStringExtra("graph") ?: "Availability"
        setContent {
            val now = rememberNow()
            val since = now / 60_000 * 60_000 - hours * 3_600_000L
            val dao = remember { MonitorDb.get(application).dao() }
            val buckets by remember(id, since) { dao.buckets(id, since, maxOf(60_000L, hours * 3_600_000L / 240)) }.collectAsStateWithLifecycle(initialValue = emptyList())
            val mode = remember { getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "System") ?: "System" }
            MaterialTheme(colorScheme = appColorScheme(mode)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeContent).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("$graph · ${hours}h", Modifier.weight(1f)); AppIconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close fullscreen") } }
                        if (graph == "Availability") BucketAvailability(buckets, since, now, Modifier.fillMaxWidth().weight(1f)) else ResponseChart(buckets.filter { it.upCount + it.downCount > 0 }.map { CheckEntry(serviceId = id, time = it.time, up = true, responseMs = it.responseMs, detail = "") }, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

@Composable fun DetailSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
private fun formatTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

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
