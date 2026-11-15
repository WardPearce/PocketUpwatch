package com.pocketupwatch

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class DashboardUiTest {
    @get:Rule val compose = createComposeRule()
    private var view: View? = null
    private val now = System.currentTimeMillis()
    private val services = listOf(
        ServiceEntry(1, "Production API", "https://api.example.com/health", 300, tags = "production, api"),
        ServiceEntry(2, "Payments API", "https://pay.example.com/status", 300, failureThreshold = 3, recoveryThreshold = 2, tags = "production, api"),
        ServiceEntry(3, "Home server", "http://192.168.1.20:8080", 600, requireValidatedInternet = false, tags = "home"),
        ServiceEntry(4, "Status website", "https://status.example.com", 300, tags = "production"),
        ServiceEntry(5, "Staging API", "https://staging.example.com", 900, enabled = false, tags = "staging")
    )
    private val checks = services.associate { service -> service.id to CheckEntry(serviceId = service.id, time = now - 60_000,
        up = service.id >= 4, responseMs = if (service.id == 2L) 480 else 124,
        detail = when (service.id) { 1L, 2L -> "HTTP 503"; 3L -> "No network connection"; else -> "HTTP 200" }, skipped = service.id == 3L) }
    private val states = services.associate { it.id to AlertState(it.id, confirmed = if (it.id == 1L) "DOWN" else "UP", downStreak = if (it.id <= 2) 1 else 0) }
    private fun scheme(dark: Boolean) = if (dark) darkColorScheme() else lightColorScheme(
        primary = Color(0xff3e5f90), onPrimary = Color.White, primaryContainer = Color(0xffd5e3ff), onPrimaryContainer = Color(0xff001b3c),
        secondary = Color(0xff535f73), secondaryContainer = Color(0xffd7e3f8), onSecondaryContainer = Color(0xff101c2b))

    @After fun reset() { MonitorService.active.value = false; MonitorService.checksInProgress.value = emptySet() }

    private fun dashboard(dark: Boolean = false, health: BackgroundHealth? = null, onDiagnostics: () -> Unit = {}, onSetAll: (Boolean) -> Unit = {}, onToggle: (ServiceEntry, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = scheme(dark)) {
                Scaffold(topBar = { AppTopBar("Pocket Upwatch", actions = {
                    AppIconButton(onClick = {}, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Default.Settings, "Settings") }
                }) }, floatingActionButton = {
                    ExtendedFloatingActionButton(onClick = {}, shape = RoundedCornerShape(8.dp), icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add monitor") })
                }) { padding ->
                    val displayedChecks = if (health == null) checks else checks.mapValues { it.value.copy(time = health.lastSaved) }
                    MonitorDashboard(services, displayedChecks, states, true, now, health, Modifier.padding(padding), onDiagnostics, onSetAll, onToggle, {})
                }
            }
        }
        compose.onNodeWithContentDescription("Sort monitors").performClick()
        compose.onNodeWithText("Status", substring = false).performClick()
    }

    @Test fun dashboardFiltersComposeAndCanBeCleared() {
        dashboard()
        compose.onNodeWithTag("filter-ALL").assertIsSelected()
        screenshot("improved-dashboard-light")
        compose.onNodeWithTag("filter-DOWN").performClick().assertIsSelected()
        compose.onNodeWithTag("monitor-1").assertExists()
        compose.onNodeWithTag("monitor-2").assertDoesNotExist()
        screenshot("improved-dashboard-filtered")
        compose.onNodeWithText("home", substring = false).performClick()
        compose.onNodeWithText("No monitors match. Try clearing your filters.").assertExists()
        compose.onNodeWithText("Clear filters").performClick()
        compose.onNodeWithText("Search monitors").performTextInput("Payments")
        compose.onNodeWithTag("monitor-2").assertExists()
        compose.onNodeWithText("Latest check failed · 1 of 3 failures").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("filter-ALL").assertIsSelected()
    }

    @Test fun dashboardAlsoRendersInDarkThemeAndLabelsEachMonitorSwitch() {
        var toggled: Pair<Long, Boolean>? = null
        dashboard(dark = true, onToggle = { service, enabled -> toggled = service.id to enabled })
        screenshot("improved-dashboard-dark")
        compose.onNodeWithContentDescription("Monitoring for Production API").performClick()
        assertEquals(1L to false, toggled)
    }

    @Test fun diagnosticsBannerNavigatesAndBulkActionsExplicitlyAffectAllMonitors() {
        var diagnostics = false
        var all: Boolean? = null
        dashboard(health = BackgroundHealth("Checks are delayed", "Some checks are overdue. Android may be delaying them.", now - 1_500_000),
            onDiagnostics = { diagnostics = true }, onSetAll = { all = it })
        compose.onNodeWithText("View diagnostics").performClick()
        assertTrue(diagnostics)
        screenshot("improved-background-health")
        compose.onNodeWithContentDescription("Actions for all monitors").performScrollTo().performClick()
        compose.onNodeWithText("Pause all monitors").performClick()
        assertEquals(false, all)
    }

    @Test
    @Config(qualifiers = "w320dp-h740dp-xhdpi")
    fun filtersRemainAccessibleOnNarrowScreensWithLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        dashboard()
        compose.onNodeWithTag("filter-PAUSED").performClick().assertIsSelected()
        compose.onNodeWithTag("monitor-5").assertExists()
        screenshot("improved-dashboard-large-text")
    }

    @Test fun failedProbeOffersPausedSaveWithoutRetestingAndPersistsConfiguration() {
        var saved: ServiceEntry? = null
        var probes = 0
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MonitorDb::class.java).allowMainThreadQueries().build()
        try {
            compose.setContent {
                view = LocalView.current
                MaterialTheme(colorScheme = scheme(false)) {
                    Scaffold(topBar = { AppTopBar("Edit monitor", onBack = {}) }) { padding ->
                        ServiceEditor(services[0], Modifier.padding(padding), onCancel = {}, probe = Probe { probes++; ProbeResult(false, "HTTP 503") }, onSave = {
                            saved = it
                            db.dao().add(it)
                        })
                    }
                }
            }
            compose.onNodeWithText("Save paused").assertDoesNotExist()
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save paused").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Check failed: HTTP 503").assertIsDisplayed()
            screenshot("improved-save-paused")
            compose.onNodeWithText("Save paused").performClick()
            compose.waitUntil(10_000) { saved != null }
            compose.waitForIdle()
            assertEquals(1, probes)
            assertFalse(runBlocking { db.dao().getService(1)!! }.enabled)
            assertEquals("production, api", saved!!.tags)
            assertEquals(300, saved!!.intervalSeconds)
            assertTrue(runBlocking { db.dao().history(1).first() }.isEmpty())
        } finally { db.close() }
    }

    @Test fun unknownProbeCanSavePausedButInvalidConfigurationCannot() {
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(services[0], onCancel = {}, probe = Probe { ProbeResult(null, "No network connection") }, onSave = { saved = it }) } }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save paused").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Name").performScrollTo().performTextClearance()
        compose.onNodeWithText("Save paused").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Offline API")
        compose.onNodeWithText("Save paused").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertFalse(saved!!.enabled)
    }

    @Test fun fullWebUrlIsSplitBetweenProtocolAndHostInTheEditor() {
        compose.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = scheme(false)) {
                Scaffold(topBar = { AppTopBar("Add monitor", onBack = {}) }) { padding ->
                    ServiceEditor(null, Modifier.padding(padding), onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = {})
                }
            }
        }
        compose.onNodeWithText("Name").performTextInput("Example website")
        compose.onNodeWithTag("monitor-target").performTextReplacement("http://example.com")
        compose.onNodeWithContentDescription("Protocol").assertTextContains("HTTP")
        compose.onNodeWithTag("monitor-target").performTextReplacement("https://example.com")
        compose.onNodeWithContentDescription("Protocol").assertTextContains("HTTPS")
        assertEquals("example.com", compose.onNodeWithTag("monitor-target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Save").assertIsEnabled()
        screenshot("improved-auto-protocol")
    }

    @Test fun pausedSaveDisablesDuplicateActionsAndRecoversFromPersistenceFailure() {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var saves = 0
        compose.setContent { MaterialTheme { ServiceEditor(services[0], onCancel = {}, probe = Probe { ProbeResult(false, "HTTP 503") }, onSave = {
            saves++
            saveStarted.complete(Unit)
            releaseSave.await()
            throw java.io.IOException("test")
        }) } }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save paused").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save paused").performClick()
        compose.waitUntil(10_000) { saveStarted.isCompleted }
        compose.onNodeWithText("Save paused").assertIsNotEnabled()
        releaseSave.complete(Unit)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn't save this monitor. Try again.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save paused").assertIsEnabled()
        assertEquals(1, saves)
    }

    @Test fun detailAndWidgetsReadPersistedAlertStateInsteadOfTheLatestOutcome() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, MonitorDb::class.java).allowMainThreadQueries().build()
        try {
            val service = services[1]
            runBlocking {
                db.dao().add(service)
                db.dao().record(checks.getValue(2))
                db.dao().saveAlertState(states.getValue(2))
                assertEquals("UP", ServiceWidgetSnapshot.load(db.dao(), 2, now, true).status)
            }
            MonitorService.active.value = true
            compose.setContent {
                view = LocalView.current
                MaterialTheme(colorScheme = scheme(false)) {
                    Scaffold(topBar = { AppTopBar(service.name, onBack = {}) }) { padding ->
                        Detail(service, MonitorViewModel(app, db.dao()), Modifier.padding(padding), {}, {})
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Latest check failed · 1 of 3 failures").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("UP").assertExists()
            screenshot("improved-confirmed-health")
        } finally { db.close() }
    }

    @Test fun appNavigationPreservesDashboardFiltersAndOpensBackgroundDiagnostics() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, MonitorDb::class.java).allowMainThreadQueries().build()
        try {
            runBlocking {
                services.take(2).forEach { db.dao().add(it) }
                checks.values.filter { it.serviceId <= 2 }.forEach { db.dao().record(it) }
                states.values.filter { it.serviceId <= 2 }.forEach { db.dao().saveAlertState(it) }
            }
            MonitorService.active.value = true
            compose.setContent { MaterialTheme { App(0, 0, "Light", {}, MonitorViewModel(app, db.dao())) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("filter-DOWN").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("filter-DOWN").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("monitor-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("monitor-1").performClick()
            compose.onNodeWithTag("service-status").assertExists()
            compose.onNodeWithTag("app-back").performClick()
            compose.onNodeWithTag("filter-DOWN").assertIsSelected()
            compose.onNodeWithTag("monitor-2").assertDoesNotExist()
            compose.runOnIdle { MonitorService.active.value = false }
            compose.onNodeWithText("View diagnostics").performClick()
            compose.onNodeWithText("Background monitoring").assertExists()
            compose.onNodeWithText("Show diagnostics").performScrollTo().performClick()
            compose.onNodeWithText("Refresh diagnostics").assertExists()
            compose.onNodeWithTag("app-back").performClick()
            compose.onNodeWithTag("filter-DOWN").assertIsSelected()
        } finally { db.close() }
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val root = requireNotNull(view).rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val path = File("build/outputs/editor-preview/$name.png").apply { parentFile!!.mkdirs() }
        path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(path.length() > 5_000)
        bitmap.recycle()
    }
}
