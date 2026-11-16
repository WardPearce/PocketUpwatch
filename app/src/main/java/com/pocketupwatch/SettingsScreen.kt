package com.pocketupwatch

import android.app.Application
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val settingPages = listOf("Monitoring", "Notifications", "Appearance and dashboard", "Data and storage", "Backup and restore", "Help and about")

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LibraryTools(
    vm: MonitorViewModel, mode: String, onMode: (String) -> Unit, modifier: Modifier,
    onExit: () -> Unit = {}, initialPage: String = "Settings", model: SettingsViewModel? = null, entryRequest: Int = 0, onLock: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val state = model ?: viewModel<SettingsViewModel>(key = "settings", factory = remember(vm) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(context.applicationContext as Application, vm.dao) as T
        }
    })
    SideEffect { onLock(state.locked) }
    DisposableEffect(Unit) { onDispose { onLock(false) } }
    val settings = rememberAppSettings()
    val prefs = remember { AppSettings.prefs(context) }
    val now = rememberNow()
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { AppNotifications.channels(context); refresh++ }
    var licenses by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(initialPage.takeIf { it in settingPages } ?: "Settings") }
    var previousEntry by rememberSaveable { mutableStateOf(initialPage) }
    var previousRequest by rememberSaveable { mutableIntStateOf(entryRequest) }
    LaunchedEffect(initialPage, entryRequest) {
        if (previousEntry != initialPage || previousRequest != entryRequest) {
            page = initialPage.takeIf { it in settingPages } ?: "Settings"
            previousEntry = initialPage
            previousRequest = entryRequest
        }
    }
    val wide = LocalAdaptiveWindow.current.workspace
    var setup by rememberSaveable { mutableStateOf(false) }
    var encryptForm by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }
    BackHandler(page != "Settings" || setup || encryptForm || state.bundle != null || state.passwordNeeded) {
        when {
            state.locked -> Unit
            state.bundle != null || state.passwordNeeded -> state.cancelImport()
            encryptForm -> { encryptForm = false; password = ""; confirmation = "" }
            setup -> setup = false
            else -> if (wide || page == initialPage && initialPage != "Settings") onExit() else page = "Settings"
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { state.writeExport(it) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { state.readImport(it) }
    LaunchedEffect(state.exportReady) {
        if (state.exportReady) {
            state.pickerOpened()
            export.launch("PocketUpwatch-${state.exportKind}-${java.time.LocalDate.now()}.json")
            encryptForm = false
        }
    }
    fun save(value: AppSettings) { AppSettings.write(prefs, value); AppNotifications.scheduleBackupReminder(context); state.refreshAlertReminders() }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { state.message = "This setting isn't available on your device." } }
    fun channel(id: String) { open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id)) }
    val diagnostics = remember(now, refresh) { BackgroundDiagnostics.prefs(context).all }
    val result = diagnostics["last_result"] as? Long ?: 0
    val errorTime = diagnostics["last_error_time"] as? Long ?: 0
    val active by MonitorService.active.collectAsStateWithLifecycle()
    val stats by remember { vm.dao.storageStats() }.collectAsStateWithLifecycle(initialValue = StorageStats())
    val hasCurrentError = errorTime > result
    val alarmAllowed = remember(now, refresh) { CheckAlarm.canScheduleExact(context) }
    val batteryAllowed = remember(now, refresh) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    // Re-read Android channel state after returning from its settings page and while this screen is visible.
    val alertState = remember(now, refresh) { AppNotifications.state(context, "alerts") }
    val monitorState = remember(now, refresh) { AppNotifications.state(context, "monitor") }
    val displayPage = if (wide && page == "Settings") "Monitoring" else page
    val categories: @Composable () -> Unit = {
        val icons = listOf(Icons.Outlined.MonitorHeart, Icons.Outlined.Notifications, Icons.Outlined.Palette, Icons.Outlined.Storage, Icons.Outlined.Backup, Icons.AutoMirrored.Outlined.HelpOutline)
        val summaries = listOf(if (active) "Running · last check ${if (result == 0L) "pending" else relativeCheckAge(result, now)}" else "Paused or stopped",
            "Service alerts: $alertState", "${settings.theme} theme", "Checks kept for ${settings.retentionDays} days",
            "Last backup: ${settingsTime(prefs.getLong("last_backup", 0))}", "Version ${BuildConfig.VERSION_NAME}")
        settingPages.forEachIndexed { index, name ->
            SettingsRow(name, summaries[index], icons[index], enabled = !state.locked, highlighted = wide && name == displayPage) { page = name; state.message = null }
            if (index < settingPages.lastIndex) HorizontalDivider()
        }
    }
    if (setup) { Onboarding { setup = false }; return }
    val settingsContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("settings-busy"))
            if (displayPage == "Backup and restore" && state.bundle != null) {
                ImportReview(state, Modifier.weight(1f))
            } else key(displayPage) { Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (displayPage) {
                    "Settings" -> { categories() }
                    "Monitoring" -> {
                        SettingsHeading("Background monitoring")
                        Text(if (active) "Monitoring is running." else "Monitoring is paused or stopped.")
                        SettingsRow("Last saved check", settingsTime(result))
                        SettingsRow("Timely alarms", if (alarmAllowed) "Allowed" else "Allow alarms for more timely checks", Icons.Outlined.Schedule) {
                            if (android.os.Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }
                        SettingsRow("Battery use", if (batteryAllowed) "Unrestricted" else "Battery optimization is on", Icons.Outlined.BatteryFull) {
                            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                        Text("Android can delay background checks.", style = MaterialTheme.typography.bodySmall)
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide diagnostics" else "Show diagnostics") }
                        if (expanded) {
                            listOf("last_alarm" to "Last alarm", "last_cycle" to "Last scheduler run", "last_result" to "Last saved check").forEach { (key, title) -> Text("$title: ${settingsTime(diagnostics[key] as? Long ?: 0)}", style = MaterialTheme.typography.bodySmall) }
                            (diagnostics["last_error"] as? String)?.let {
                                Text(if (hasCurrentError) "Current error: $it" else "Previous error: $it · resolved", color = if (hasCurrentError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(settingsTime(errorTime), style = MaterialTheme.typography.bodySmall)
                            }
                            AppSecondaryButton(onClick = { refresh++ }) { Text("Refresh diagnostics") }
                            AppSecondaryButton(onClick = { state.makeReport() }, enabled = !state.locked) { Text("Review support report") }
                        }
                        HorizontalDivider()
                        SettingsHeading("New monitor defaults")
                        Text("Existing monitors won't change.", style = MaterialTheme.typography.bodySmall)
                        IntervalSlider(settings.intervalSeconds, settings.intervalSeconds) { save(settings.copy(intervalSeconds = it)) }
                        SettingsChoice("Timeout", "${settings.timeoutSeconds} sec", listOf(1, 5, 10, 15, 20, 30, 60).map { "$it sec" }) { save(settings.copy(timeoutSeconds = it.substringBefore(' ').toInt())) }
                        EditorToggle("Require Internet", "Turn off for local services without Internet access.", settings.requireInternet, true) { save(settings.copy(requireInternet = it)) }
                        ConfirmationSlider(settings.failures, recovery = false) { save(settings.copy(failures = it)) }
                        ConfirmationSlider(settings.recoveries, recovery = true) { save(settings.copy(recoveries = it)) }
                    }
                    "Notifications" -> {
                        SettingsRow("Service alerts", alertState, Icons.Outlined.NotificationsActive) { channel("alerts") }
                        SettingsRow("Monitoring status", monitorState, Icons.Outlined.NotificationsNone) { channel("monitor") }
                        SettingsRow("Backup reminders", AppNotifications.state(context, "backup"), Icons.Outlined.Backup) { channel("backup") }
                        Text("Choose sounds and vibration in Android settings.", style = MaterialTheme.typography.bodySmall)
                        AppButton(onClick = { state.message = AppNotifications.test(context) }) { Text("Test alert") }
                        HorizontalDivider()
                        val snoozeUntil = prefs.getLong("snooze_until", 0)
                        SettingsChoice("Snooze service alerts", if (snoozeUntil > now) "Until ${settingsTime(snoozeUntil)}" else "Off", listOf("Off", "1 hour", "4 hours", "24 hours")) {
                            val hours = it.substringBefore(' ').toLongOrNull() ?: 0
                            prefs.edit().putLong("snooze_until", if (hours == 0L) 0 else now + hours * 3_600_000).apply(); state.refreshAlertReminders(); refresh++
                        }
                        EditorToggle("Quiet hours", "Mute service alerts on a daily schedule.", settings.quietHours, true) { save(settings.copy(quietHours = it)) }
                        if (settings.quietHours) {
                            SettingsRow("Starts", clock(settings.quietStart)) { TimePickerDialog(context, { _, hour, minute -> save(settings.copy(quietStart = hour * 60 + minute)) }, settings.quietStart / 60, settings.quietStart % 60, true).show() }
                            SettingsRow("Ends", clock(settings.quietEnd)) { TimePickerDialog(context, { _, hour, minute -> save(settings.copy(quietEnd = hour * 60 + minute)) }, settings.quietEnd / 60, settings.quietEnd % 60, true).show() }
                            Text(if (settings.quietStart == settings.quietEnd) "Choose different start and end times to enable the schedule." else "Uses your device's time zone.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Checks continue while alerts are muted. Ongoing outages can remind you when alerts resume.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Appearance and dashboard" -> {
                        SettingsChoice("Theme", mode, listOf("System", "Light", "Dark")) { onMode(it); save(settings.copy(theme = it)) }
                        SettingsChoice("Default sort", settings.sort, listOf("Newest", "Name", "Status", "Paused first", "Tag")) { save(settings.copy(sort = it)) }
                        val periods = mapOf("1 hour" to 1, "24 hours" to 24, "7 days" to 168, "30 days" to 720)
                        SettingsChoice("Default chart period", periods.entries.first { it.value == settings.chartHours }.key, periods.keys.toList()) { save(settings.copy(chartHours = periods.getValue(it))) }
                        SettingsChoice("Monitor list", if (settings.compact) "Compact" else "Comfortable", listOf("Comfortable", "Compact")) { save(settings.copy(compact = it == "Compact")) }
                        SettingsChoice("Check times", if (settings.absoluteTimes) "Date and time" else "Relative", listOf("Relative", "Date and time")) { save(settings.copy(absoluteTimes = it == "Date and time")) }
                    }
                    "Data and storage" -> {
                        SettingsChoice("Keep check history", "${settings.retentionDays} days", listOf(30, 90, 180, 365).map { "$it days" }) { save(settings.copy(retentionDays = it.substringBefore(' ').toInt())) }
                        Text("Hourly summaries stay for a year. Incidents stay until you delete their monitor. Cleanup always keeps each monitor's latest check.", style = MaterialTheme.typography.bodySmall)
                        Text("${stats.checks} checks · ${stats.incidents} incidents · ${stats.summaries} hourly summaries")
                        Text("Oldest check: ${settingsTime(stats.oldest ?: 0)}")
                        val bytes = remember(now, refresh, stats) { listOf("monitor.db", "monitor.db-wal", "monitor.db-shm").sumOf { context.getDatabasePath(it).length() } }
                        Text("Database files: ${android.text.format.Formatter.formatFileSize(context, bytes)}")
                        Text("Last cleanup: ${settingsTime(prefs.getLong("last_cleanup", 0))}")
                        Text("Automatic cleanup runs during monitoring. You can clean up now, even while monitors are paused.", style = MaterialTheme.typography.bodySmall)
                        AppSecondaryButton(onClick = { state.previewCleanup(settings.retentionDays) }, enabled = !state.locked) { Text("Clean up old checks") }
                    }
                    "Backup and restore" -> {
                        Text("Last backup: ${settingsTime(prefs.getLong("last_backup", 0))}")
                        SettingsChoice("Remind me to back up", if (settings.backupReminderDays == 0) "Off" else "Every ${settings.backupReminderDays} days", listOf("Off", "Every 7 days", "Every 30 days", "Every 90 days")) {
                            save(settings.copy(backupReminderDays = Regex("[0-9]+").find(it)?.value?.toInt() ?: 0))
                        }
                        if (state.passwordNeeded) {
                            SettingsHeading("Unlock backup")
                            PasswordField("Backup password", password, { password = it }, showPassword)
                            EditorToggle("Show password", "", showPassword, !state.locked) { showPassword = it }
                            AppButton(onClick = { state.unlockImport(password.toCharArray()); password = "" }, enabled = !state.locked && password.isNotEmpty()) { Text("Unlock") }
                            TextButton(onClick = { state.cancelImport(); password = "" }, enabled = !state.locked) { Text("Cancel") }
                        } else if (encryptForm) {
                            SettingsHeading("Create encrypted backup")
                            Text("Includes monitors and credentials. Keep your password somewhere safe; it can't be recovered.", style = MaterialTheme.typography.bodySmall)
                            PasswordField("Password", password, { password = it }, showPassword)
                            PasswordField("Confirm password", confirmation, { confirmation = it }, showPassword)
                            Text(if (password.isNotEmpty() && password.length < 12) "Use at least 12 characters." else if (confirmation.isNotEmpty() && password != confirmation) "The passwords don't match." else "Use 12 or more characters.", style = MaterialTheme.typography.bodySmall)
                            EditorToggle("Show password", "", showPassword, !state.locked) { showPassword = it }
                            EditorToggle("Include app preferences", "Theme, display choices, defaults and alert schedules. History and Android permissions aren't included.", includeSettings, !state.locked) { includeSettings = it }
                            AppButton(onClick = { state.prepareExport(password.toCharArray(), includeSettings); password = ""; confirmation = "" }, enabled = !state.locked && password.length >= 12 && password == confirmation) { Text("Choose where to save") }
                            TextButton(onClick = { encryptForm = false; password = ""; confirmation = "" }, enabled = !state.locked) { Text("Cancel") }
                        } else {
                            SettingsRow("Create encrypted backup", "Monitors, credentials and optional app preferences", Icons.Outlined.Lock, enabled = !state.locked) { encryptForm = true }
                            SettingsRow("Export redacted template", "Leaves out credentials and request/response bodies", Icons.Outlined.Share, enabled = !state.locked) { state.prepareExport(charArrayOf(), false, template = true) }
                            Text("Check a template before sharing it. Monitor names, hosts and paths remain visible.", style = MaterialTheme.typography.bodySmall)
                            SettingsRow("Import a backup", "Choose a file, then review what to restore", Icons.Outlined.Restore, enabled = !state.locked) { state.importPickerOpened(); import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                            Text("Imported monitors start paused. Redacted templates need a review before you can start them.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    "Help and about" -> {
                        SettingsHeading("Pocket Upwatch")
                        SelectionContainer { Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
                        SettingsRow("Set up permissions", "Notifications, battery use and alarms", Icons.Outlined.Security) { setup = true }
                        SettingsHeading("Widgets and Quick Settings")
                        Text("Touch and hold your home screen, open Widgets, then choose Pocket Upwatch. The Service uptime widget lets you pick a monitor.")
                        Text("To add the pause/resume tile, open Quick Settings and tap Edit. Look for Pocket Upwatch.")
                        SettingsRow("Open-source licenses", "Editor, parsers and library notices", Icons.Outlined.Description) { licenses = true }
                        SettingsRow("Release notes", "What's new", Icons.Outlined.NewReleases) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WardPearce/PocketUpwatch/releases"))) }
                        SettingsRow("Project and source code", "GitHub", Icons.Outlined.Code) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WardPearce/PocketUpwatch"))) }
                        SettingsRow("Report an issue", "Open the issue form on GitHub", Icons.Outlined.Feedback) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WardPearce/PocketUpwatch/issues/new"))) }
                        AppSecondaryButton(onClick = { state.makeReport() }, enabled = !state.locked) { Text("Review support report") }
                    }
                }
            } }
            state.message?.let { text -> Snackbar(action = { TextButton(onClick = { state.message = null }) { Text("Dismiss") } }, modifier = Modifier.padding(12.dp)) { Text(text) } }
        }
    }
    if (licenses) {
        val notices = remember { listOf("OPEN-SOURCE.txt", "SORA-LICENSE.txt", "EPL-2.0.txt", "APACHE-2.0.txt", "REGEX-LICENSES.txt").joinToString("\n\n") { context.assets.open(it).bufferedReader().use { reader -> reader.readText() } } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open-source licenses") }, text = { SelectionContainer { Text(notices, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
    }
    val currentContent by rememberUpdatedState(settingsContent)
    val pageContent = remember { movableContentOf { currentContent() } }
    Scaffold(modifier, topBar = {
        AppTopBar(if (state.bundle != null && displayPage == "Backup and restore") "Review import" else if (wide) "Settings" else page, onBack = {
            when {
                state.locked -> Unit
                state.bundle != null || state.passwordNeeded -> state.cancelImport()
                encryptForm -> { encryptForm = false; password = ""; confirmation = "" }
                wide || page == "Settings" -> onExit()
                else -> if (page == initialPage && initialPage != "Settings") onExit() else page = "Settings"
            }
        })
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (wide) AdaptivePanes(primaryWidth = 280.dp, primary = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { categories() }
            }, secondary = {
                Column(Modifier.fillMaxSize()) {
                    Text(if (state.bundle != null) "Review import" else displayPage, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp).testTag("settings-page-title"))
                    Box(Modifier.weight(1f)) { pageContent() }
                }
            }) else pageContent()
        }
    }
    state.cleanupPreview?.let { count -> AlertDialog(onDismissRequest = { state.cancelCleanup() }, title = { Text("Remove $count old checks?") },
        text = { Text("Keeps each monitor's latest check. Hourly summaries older than a year are also removed. Incidents are kept. Deleted checks can't be restored, and file size may not shrink right away.") },
        confirmButton = { AppButton(onClick = { state.cleanUp() }, enabled = !state.locked) { Text("Clean up") } },
        dismissButton = { TextButton(onClick = { state.cancelCleanup() }, enabled = !state.locked) { Text("Cancel") } }) }
    state.report?.let { report -> AlertDialog(onDismissRequest = { state.closeReport() }, title = { Text("Support report") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Review before sharing. Includes app/Android versions, permission states and error categories. Monitor addresses and credentials are left out.")
            SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { AppButton(onClick = { state.exportReport(); state.closeReport() }) { Text("Save report") } },
        dismissButton = { TextButton(onClick = { state.closeReport() }) { Text("Close") } }) }
}

@Composable private fun ImportReview(state: SettingsViewModel, modifier: Modifier) {
    val data = state.bundle ?: return
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Choose monitors to import. Existing monitors and history are kept.") }
            if (state.duplicates.isNotEmpty()) item { Text("Possible duplicates start unselected. Select them to import another copy.", style = MaterialTheme.typography.bodySmall) }
            if (data.services.any { it.needsReview }) item { Text("Templates leave out private values. Review credentials, query values and response rules before starting imported monitors.", style = MaterialTheme.typography.bodySmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !state.locked, onClick = { state.selected = data.services.indices.toSet() }) { Text("Select all") }
                TextButton(enabled = !state.locked, onClick = { state.selected = emptySet() }) { Text("Clear selection") }
            } }
            itemsIndexed(data.services) { index, service ->
                ListItem(headlineContent = { Text(service.name) }, supportingContent = { Column {
                    Text(displayTarget(service.url))
                    if (index in state.duplicates) Text("Possible duplicate", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (service.needsReview) Text("Needs review")
                } }, leadingContent = { Checkbox(checked = index in state.selected, enabled = !state.locked, onCheckedChange = { checked -> state.selected = if (checked) state.selected + index else state.selected - index }) },
                    modifier = Modifier.clickable(enabled = !state.locked) { state.selected = if (index in state.selected) state.selected - index else state.selected + index })
                HorizontalDivider()
            }
            if (data.settings != null) item { EditorToggle("Restore app preferences", "Includes appearance, monitor defaults and alert schedules.", state.restoreSettings, !state.locked) { state.restoreSettings = it } }
        }
        Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppSecondaryButton(onClick = { state.cancelImport() }, enabled = !state.locked, modifier = Modifier.weight(1f)) { Text("Cancel") }
            AppButton(onClick = { state.importSelected() }, enabled = !state.locked && (state.selected.isNotEmpty() || state.restoreSettings), modifier = Modifier.weight(1f).testTag("confirm-import")) { Text(if (state.busy) "Importing…" else "Import ${state.selected.size}") }
        } }
    }
}

@Composable private fun SettingsHeading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
@Composable private fun SettingsRow(title: String, summary: String, icon: ImageVector? = null, enabled: Boolean = true, highlighted: Boolean = false, onClick: (() -> Unit)? = null) {
    ListItem(colors = ListItemDefaults.colors(containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface), headlineContent = { Text(title) }, supportingContent = if (summary.isNotEmpty()) ({ Text(summary) }) else null,
        leadingContent = icon?.let { { Icon(it, null) } }, trailingContent = onClick?.let { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick)))
}
@Composable private fun SettingsChoice(title: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { SettingsRow(title, value) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; onChange(item) }) } }
    }
}
@Composable private fun PasswordField(title: String, value: String, onChange: (String) -> Unit, visible: Boolean) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(title) }, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation())
}
private fun clock(minutes: Int): String = String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)
