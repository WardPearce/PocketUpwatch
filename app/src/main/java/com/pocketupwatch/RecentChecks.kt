package com.pocketupwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

@Composable internal fun RecentChecks(id: Long, dao: MonitorDao) {
    val version by remember(id) { dao.historyVersion(id) }.collectAsStateWithLifecycle(initialValue = HistoryVersion())
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    var anchor by rememberSaveable(id) { mutableLongStateOf(0) }
    var data by remember(id) { mutableStateOf<HistoryPage?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf(false) }
    var retry by remember(id) { mutableIntStateOf(0) }
    var sort by rememberSaveable(id) { mutableStateOf("Newest first") }
    val historyScroll = rememberScrollState()
    LaunchedEffect(id, page, version, retry) {
        loading = true
        error = false
        try {
            val loaded = dao.historyPage(id, page, if (page == 0) 0 else anchor)
            data = loaded
            anchor = loaded.anchorId
            page = loaded.page
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    LaunchedEffect(data?.page, sort, id) { historyScroll.scrollTo(0) }
    val checks = data?.checks.orEmpty()
    val sorted = remember(checks, sort) {
        when (sort) {
            "Oldest first" -> checks.reversed()
            "Fastest first" -> checks.sortedBy { it.responseMs }
            "Slowest first" -> checks.sortedByDescending { it.responseMs }
            "Down first" -> checks.sortedBy { if (it.skipped) 1 else if (it.up) 2 else 0 }
            else -> checks
        }
    }
    DetailSection(Modifier.testTag("recent-checks")) {
        Text("Recent checks", style = MaterialTheme.typography.titleLarge)
        ChoiceMenu("Page sort", sort, listOf("Newest first", "Oldest first", "Fastest first", "Slowest first", "Down first")) { sort = it }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("history-loading"))
        if (error) {
            Text("Could not load history. Please try again.", color = MaterialTheme.colorScheme.error)
            AppSecondaryButton(onClick = { retry++ }) { Text("Retry history") }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(historyScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sorted.forEach { check ->
                Column(Modifier.fillMaxWidth().testTag("check-${check.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(if (check.skipped) "UNKNOWN" else if (check.up) "UP" else "DOWN")
                        Text(if (check.skipped) "—" else "${check.responseMs} ms", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(check.detail, style = MaterialTheme.typography.bodyMedium)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(check.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            if (!loading && !error && checks.isEmpty()) Text("No checks yet")
        }
        val current = data?.page ?: 0
        val pages = data?.pageCount ?: 1
        Text("Page ${current + 1} of $pages", Modifier.align(Alignment.CenterHorizontally).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppSecondaryButton(enabled = !loading && !error && current > 0, modifier = Modifier.weight(1f).fillMaxHeight().testTag("history-previous"), onClick = { loading = true; page = current - 1 }) { Text("Previous") }
            AppSecondaryButton(enabled = !loading && !error && current + 1 < pages, modifier = Modifier.weight(1f).fillMaxHeight().testTag("history-next"), onClick = { loading = true; page = current + 1 }) { Text("Next") }
        }
    }
}
