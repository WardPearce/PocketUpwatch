package com.pocketupwatch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class DashboardFilter(val title: String) {
    ALL("All"), DOWN("Down"), UNKNOWN("Unknown"), PAUSED("Paused");

    fun accepts(status: String): Boolean = when (this) {
        ALL -> true
        DOWN -> status == "DOWN"
        UNKNOWN -> status in listOf("UNKNOWN", "STALE", "STOPPED", "PENDING")
        PAUSED -> status == "PAUSED"
    }
}

internal fun monitorTags(service: ServiceEntry): List<String> =
    service.tags.split(',').map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

internal fun dashboardMonitors(
    services: List<ServiceEntry>, checks: Map<Long, CheckEntry>, states: Map<Long, AlertState>,
    active: Boolean, now: Long, search: String, tag: String, filter: DashboardFilter, sort: String
): List<ServiceEntry> {
    fun status(service: ServiceEntry) = MonitorStatus.label(service, checks[service.id], active, now, states[service.id])
    val matching = services.filter { service ->
        "${service.name} ${displayTarget(service.url)} ${service.tags}".contains(search.trim(), true) &&
            (tag.isEmpty() || monitorTags(service).any { it.equals(tag, true) }) && filter.accepts(status(service))
    }
    return when (sort) {
        "Name" -> matching.sortedBy { it.name.lowercase() }
        "Paused first" -> matching.sortedBy { it.enabled }
        "Tag" -> matching.sortedBy { it.tags.lowercase() }
        "Status" -> matching.sortedWith(compareBy<ServiceEntry> {
            when (status(it)) { "DOWN" -> 0; "STALE" -> 1; "UNKNOWN" -> 2; "STOPPED" -> 3; "PENDING" -> 4; "UP" -> 5; else -> 6 }
        }.thenBy { it.name.lowercase() })
        else -> matching.sortedByDescending { it.id }
    }
}

internal data class BackgroundHealth(val title: String, val reason: String, val lastSaved: Long)

internal fun backgroundHealth(
    services: List<ServiceEntry>, checks: Map<Long, CheckEntry>, active: Boolean, now: Long,
    lastSaved: Long, lastErrorTime: Long, lastError: String?, notificationsAllowed: Boolean, exactAlarmsAllowed: Boolean
): BackgroundHealth? {
    val enabled = services.filter { it.enabled }
    if (enabled.isEmpty()) return null
    val last = maxOf(lastSaved, checks.values.maxOfOrNull { it.time } ?: 0)
    val delayed = enabled.any { it.maintenanceUntil <= now && checks[it.id]?.let { check ->
        now - check.time > maxOf(it.intervalSeconds * 2_000L, 120_000L)
    } == true }
    val issue = when {
        !active -> "Monitoring stopped" to "Enabled monitors are not running. Open diagnostics to review background access."
        lastErrorTime > last && lastError != null -> "Background work needs attention" to lastError
        delayed -> "Checks are delayed" to "Some monitors have missed their expected checks. Android may be delaying background work."
        !notificationsAllowed -> "Alerts are turned off" to "Allow notifications in Android settings to receive service alerts."
        !exactAlarmsAllowed -> "Scheduled checks may be delayed" to "Allow timely alarms to improve checks while your phone sleeps."
        else -> return null
    }
    return BackgroundHealth(issue.first, issue.second, last)
}

internal fun relativeCheckAge(time: Long, now: Long): String {
    val seconds = ((now - time).coerceAtLeast(0) / 1_000)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

@Composable internal fun BackgroundHealthBanner(health: BackgroundHealth, now: Long, onDiagnostics: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("background-health"), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(health.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text(health.reason, style = MaterialTheme.typography.bodySmall)
            Text(if (health.lastSaved == 0L) "No saved checks yet" else "Last saved check ${relativeCheckAge(health.lastSaved, now)}", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onDiagnostics, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("View diagnostics")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable internal fun MonitorDashboard(
    services: List<ServiceEntry>, checks: Map<Long, CheckEntry>, states: Map<Long, AlertState>, active: Boolean,
    now: Long, health: BackgroundHealth?, modifier: Modifier = Modifier, onDiagnostics: () -> Unit,
    onSetAll: (Boolean) -> Unit, onToggle: (ServiceEntry, Boolean) -> Unit, onSelect: (ServiceEntry) -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("Newest") }
    var filter by rememberSaveable { mutableStateOf(DashboardFilter.ALL) }
    var tag by rememberSaveable { mutableStateOf("") }
    val tags = services.flatMap(::monitorTags).distinctBy(String::lowercase).sortedBy(String::lowercase)
    // A removed tag must not leave an invisible, impossible-to-clear filter behind.
    val effectiveTag = tag.takeIf { selected -> tags.any { it.equals(selected, true) } }.orEmpty()
    val statuses = services.map { MonitorStatus.label(it, checks[it.id], active, now, states[it.id]) }
    val visible = dashboardMonitors(services, checks, states, active, now, search, effectiveTag, filter, sort)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(if (active) "Monitoring active" else if (services.none { it.enabled }) "All paused" else "Stopped", style = MaterialTheme.typography.labelMedium)
                }
                BoxWithConstraints {
                    val columns = if (maxWidth < 350.dp || LocalDensity.current.fontScale > 1.3f) 2 else 4
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardFilter.entries.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { choice ->
                                    val count = statuses.count(choice::accepts)
                                    OutlinedCard(onClick = { filter = choice }, modifier = Modifier.weight(1f).testTag("filter-${choice.name}").semantics {
                                        selected = filter == choice
                                        contentDescription = "${choice.title}, $count monitors"
                                    }, shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.outlinedCardColors(containerColor = if (filter == choice) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                                        border = BorderStroke(1.dp, if (filter == choice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                            Text(choice.title, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (health != null) item { BackgroundHealthBanner(health, now, onDiagnostics) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search monitors") },
                    placeholder = { Text("Name, target or tag") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
                        if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search") }
                    })
                if (tags.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = effectiveTag.isEmpty(), onClick = { tag = "" }, label = { Text("All tags") })
                    tags.forEach { value -> FilterChip(selected = value.equals(effectiveTag, true), onClick = { tag = if (value.equals(effectiveTag, true)) "" else value }, label = { Text(value) }) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${visible.size} ${if (visible.size == 1) "monitor" else "monitors"}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    DashboardMenu(sort, listOf("Newest", "Name", "Status", "Paused first", "Tag"), "Sort monitors") { sort = it }
                    var actions by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { actions = true }) { Icon(Icons.Default.MoreVert, "Actions for all monitors") }
                        DropdownMenu(actions, { actions = false }) {
                            DropdownMenuItem(text = { Text("Pause all monitors") }, enabled = services.any { it.enabled }, onClick = { actions = false; onSetAll(false) })
                            DropdownMenuItem(text = { Text("Resume all monitors") }, enabled = services.any { !it.enabled }, onClick = { actions = false; onSetAll(true) })
                        }
                    }
                }
                if (filter == DashboardFilter.UNKNOWN) Text("Unknown includes skipped, delayed, stopped and pending checks.", style = MaterialTheme.typography.bodySmall)
                if (filter != DashboardFilter.ALL || effectiveTag.isNotEmpty() || search.isNotEmpty()) TextButton(onClick = { filter = DashboardFilter.ALL; tag = ""; search = "" }) { Text("Clear filters") }
            }
        }
        if (visible.isEmpty()) item {
            Text(if (services.isEmpty()) "Add your first monitor to start checking." else "No matching monitors. Try another filter or clear your search.", modifier = Modifier.padding(vertical = 16.dp))
        }
        items(visible, key = { it.id }) { service ->
            MonitorCard(service, checks[service.id], states[service.id], active, now, onToggle = { onToggle(service, it) }, onClick = { onSelect(service) })
        }
        item { Text("Checks reflect this device and network. Unknown gaps are excluded from success rates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun DashboardMenu(value: String, choices: List<String>, description: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = description }) {
            Text(value)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onChange(choice); expanded = false }) }
        }
    }
}

@Composable internal fun MonitorCard(service: ServiceEntry, latest: CheckEntry?, state: AlertState?, active: Boolean, now: Long, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val status = MonitorStatus.label(service, latest, active, now, state)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("monitor-${service.id}"), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(service.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(displayTarget(service.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                Switch(service.enabled, onToggle, modifier = Modifier.semantics {
                    contentDescription = "Monitoring for ${service.name}"
                    stateDescription = if (service.enabled) "Enabled" else "Paused"
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StatusBadge(status)
                latest?.takeUnless { it.skipped }?.let { Text("${it.responseMs} ms", style = MaterialTheme.typography.labelLarge) }
            }
            HealthEvidence(service, latest, state, status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(latest?.let { "Checked ${relativeCheckAge(it.time, now)}" } ?: "No checks yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (service.tags.isNotBlank()) Text(service.tags, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable internal fun HealthEvidence(service: ServiceEntry, latest: CheckEntry?, state: AlertState?, displayedStatus: String) {
    val confirmed = MonitorStatus.confirmed(service, latest, state)
    if (confirmed != "UNKNOWN" && displayedStatus != confirmed) Text("Last confirmed health: $confirmed", style = MaterialTheme.typography.labelMedium)
    Text(MonitorStatus.evidence(service, latest, state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (latest != null && !latest.skipped && (latest.up && confirmed == "DOWN" || !latest.up && confirmed != "DOWN")) {
        Text(latest.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
