package com.pocketupwatch

import android.app.Application
import android.app.NotificationManager
import android.app.AlarmManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsFeaturesTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, MonitorDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private val model = SettingsViewModel(app, dao)
    private var view: View? = null
    private val service = ServiceEntry(name = "Production API", url = "https://example.com/health?token=private", intervalSeconds = 300)
    @Before fun setup() { AppSettings.prefs(app).edit().clear().commit(); BackgroundDiagnostics.prefs(app).edit().clear().commit() }
    @After fun close() { MonitorService.active.value = false; db.close(); AppSettings.prefs(app).edit().clear().commit() }
    private fun waitWork() = compose.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !model.busy }
    private fun show(page: String = "Settings", dark: Boolean = false) {
        compose.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                LibraryTools(MonitorViewModel(app, dao), if (dark) "Dark" else "Light", {}, Modifier, initialPage = page, model = model)
            }
        }
    }

    @Test fun settingsGroupsAndBackNavigationAreClear() {
        show()
        compose.onNodeWithText("Monitoring").assertExists()
        compose.onNodeWithText("Backup and restore").assertExists()
        screenshot("settings-overview-light")
        compose.onNodeWithText("Appearance and dashboard").performClick()
        compose.onNodeWithText("Default sort").assertExists()
        compose.onNodeWithText("Default sort").performClick()
        compose.onNodeWithText("Status").performClick()
        assertEquals("Status", AppSettings.read(AppSettings.prefs(app)).sort)
        compose.onNodeWithText("Monitor list").performClick()
        compose.onNodeWithText("Compact").performClick()
        assertTrue(AppSettings.read(AppSettings.prefs(app)).compact)
        compose.onNodeWithTag("app-back").performClick()
        compose.onNodeWithText("Backup and restore").assertExists()
    }
    @Test fun settingsAlsoRenderInDarkMode() { show(dark = true); screenshot("settings-overview-dark") }
    @Test fun recreationKeepsTheCurrentBackupPageAndPendingExport() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { LibraryTools(MonitorViewModel(app, dao), "System", {}, Modifier, model = model) } }
        compose.onNodeWithText("Backup and restore").performClick()
        model.prepareExport(charArrayOf(), false, template = true); waitWork()
        compose.waitForIdle()
        assertTrue(model.locked)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Import a backup").assertExists()
        assertTrue(model.locked)
        assertEquals("template", model.exportKind)
        model.writeExport(null)
    }
    @Test fun displayStorageAndHelpPagesExposeTheirActions() {
        show()
        compose.onNodeWithText("Appearance and dashboard").performClick()
        compose.onNodeWithText("Default chart period").performClick()
        compose.onNodeWithText("7 days").performClick()
        assertEquals(168, AppSettings.read(AppSettings.prefs(app)).chartHours)
        screenshot("settings-appearance")
        compose.onNodeWithTag("app-back").performClick()
        compose.onNodeWithText("Data and storage").performClick()
        compose.onNodeWithText("Clean up old checks").assertExists()
        screenshot("settings-storage")
        compose.onNodeWithTag("app-back").performClick()
        compose.onNodeWithText("Help and about").performClick()
        compose.onNodeWithText("Set up permissions").assertExists()
        screenshot("settings-help")
    }

    @Test fun backupActionsAreSeparateAndPasswordsStayInsideTheirFlow() {
        show("Backup and restore")
        compose.onNodeWithText("Create encrypted backup").assertExists()
        compose.onNodeWithText("Export redacted template").assertExists()
        compose.onNodeWithText("Password").assertDoesNotExist()
        screenshot("settings-backup")
        compose.onNodeWithText("Create encrypted backup").performClick()
        compose.onNodeWithText("Password").performTextInput("short")
        compose.onNodeWithText("Confirm password").performTextInput("short")
        compose.onNodeWithText("Choose where to save").assertIsNotEnabled()
        compose.onNodeWithText("Password").performTextReplacement("long enough password")
        compose.onNodeWithText("Confirm password").performTextReplacement("long enough password")
        compose.onNodeWithText("Choose where to save").assertIsEnabled()
        screenshot("settings-encrypted-backup")
    }
    @Test fun diagnosticsResolveOldErrorsAndRefreshWhenResultsArrive() {
        val now = System.currentTimeMillis()
        BackgroundDiagnostics.prefs(app).edit().putLong("last_error_time", now - 1000).putString("last_error", "Scheduler: IOException").putLong("last_result", now).commit()
        show("Monitoring")
        compose.onNodeWithText("Show diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Previous error: Scheduler: IOException · resolved").assertExists()
        screenshot("settings-monitoring")
        compose.runOnIdle { BackgroundDiagnostics.prefs(app).edit().putLong("last_error_time", now + 1000).apply() }
        compose.onNodeWithText("Refresh diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Current error: Scheduler: IOException").assertExists()
    }
    @Test fun notificationChannelsAndTestAlertRespectAndroidBlocking() {
        show("Notifications")
        compose.onNodeWithText("Test alert").performClick()
        compose.onNodeWithText("Test alert sent.").assertExists()
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.any { it.extras.getString("android.title") == "Test alert" })
        manager.deleteNotificationChannel("alerts")
        manager.createNotificationChannel(android.app.NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_NONE))
        assertFalse(AppNotifications.allowed(app, "alerts"))
        assertEquals("Allow service alerts in Android settings first.", AppNotifications.test(app))
        screenshot("settings-notifications")
    }
    @Test fun confirmationDefaultSlidersDoNotChangeExistingMonitors() = runBlocking {
        AppSettings.write(AppSettings.prefs(app), AppSettings(failures = 3, recoveries = 2))
        val id = dao.add(service.copy(failureThreshold = 3, recoveryThreshold = 2))
        show("Monitoring")
        compose.onNodeWithTag("failure-threshold-slider").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(20f) }
        compose.onNodeWithTag("recovery-threshold-slider").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1f) }
        assertEquals(20, AppSettings.read(AppSettings.prefs(app)).failures)
        assertEquals(1, AppSettings.read(AppSettings.prefs(app)).recoveries)
        assertEquals(3, dao.getService(id)!!.failureThreshold)
        assertEquals(2, dao.getService(id)!!.recoveryThreshold)
    }
    @Test fun newDefaultsApplyOnlyToNewMonitorsAndInputsHaveEqualBounds() {
        AppSettings.write(AppSettings.prefs(app), AppSettings(intervalSeconds = 7200, timeoutSeconds = 12, failures = 3, recoveries = 2, requireInternet = false))
        var saved: ServiceEntry? = null
        compose.setContent {
            view = LocalView.current
            MaterialTheme { Surface { ServiceEditor(null, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = { saved = it }) } }
        }
        compose.onNodeWithText("Name").performTextInput("Home API")
        compose.onNodeWithTag("monitor-target").performTextInput("http://example.com")
        val protocol = compose.onNodeWithContentDescription("Protocol").fetchSemanticsNode().boundsInRoot
        val address = compose.onNodeWithTag("monitor-target").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(protocol.top - address.top) < 1f)
        assertTrue(abs(protocol.bottom - address.bottom) < 1f)
        assertTrue(abs(protocol.height - address.height) < 1f)
        compose.onNodeWithText("Add a name and target to enable Save.").assertDoesNotExist()
        screenshot("aligned-monitor-editor")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(7200, saved!!.intervalSeconds)
        assertEquals(12, saved!!.timeoutSeconds)
        assertEquals(3, saved!!.failureThreshold)
        assertFalse(saved!!.requireValidatedInternet)
    }
    @Test fun encryptedBackupsRoundTripPreferencesAndRejectMalformedValues() {
        val settings = AppSettings(theme = "Dark", compact = true, failures = 3, quietHours = true)
        val password = "long enough password".toCharArray()
        val text = BackupCodec.encodeBundle(listOf(service), password, settings) { emptyMap() }
        val result = BackupCodec.decodeBundle(text, password) { "" }
        assertEquals(settings, result.settings)
        assertFalse(result.services.single().enabled)
        assertNull(BackupCodec.decodeBundle(BackupCodec.encode(listOf(service), charArrayOf()) { emptyMap() }, charArrayOf()) { "" }.settings)
        assertThrows(IllegalArgumentException::class.java) { AppSettings.fromJson(JsonParser.parseString("{\"quietHours\":\"false\"}").asJsonObject) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings.fromJson(JsonParser.parseString("{\"intervalSeconds\":4294967596}").asJsonObject) }
    }
    @Test @Config(qualifiers = "w320dp-h900dp-xxhdpi") fun protocolAndAddressStayAlignedWithLargeText() {
        compose.setContent {
            view = LocalView.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(3f, 1.5f)) {
                MaterialTheme { Surface { ServiceEditor(null, onCancel = {}, onSave = {}) } }
            }
        }
        compose.onNodeWithTag("monitor-target").performTextInput("https://example.com")
        val protocol = compose.onNodeWithContentDescription("Protocol").fetchSemanticsNode().boundsInRoot
        val address = compose.onNodeWithTag("monitor-target").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(protocol.top - address.top) < 1f)
        assertTrue(abs(protocol.bottom - address.bottom) < 1f)
        assertTrue(address.width >= 96 * 3)
        screenshot("aligned-editor-large-text")
    }
    @Test fun importPreviewSelectsUniqueRowsAndGuardsRepeatedSubmissions() = runBlocking {
        dao.add(service)
        val second = service.copy(name = "Home", url = "http://192.168.1.2")
        val backup = BackupCodec.encode(listOf(service, second, second), charArrayOf()) { emptyMap() }
        model.loadImport(backup)
        assertEquals(setOf(0, 2), model.duplicates)
        assertEquals(setOf(1), model.selected)
        show("Backup and restore")
        compose.onNodeWithText("Review import").assertExists()
        screenshot("settings-import-review")
        val gate = CompletableDeferred<Unit>()
        val tracked = object : MonitorDao by dao { override suspend fun importSelection(services: List<ServiceEntry>): List<Long> { gate.await(); return dao.importSelection(services) } }
        val guarded = SettingsViewModel(app, tracked)
        guarded.loadImport(backup)
        guarded.importSelected(); guarded.importSelected()
        assertTrue(guarded.busy)
        gate.complete(Unit)
        compose.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !guarded.busy }
        assertEquals(2, dao.allServices().size)
        val imported = dao.allServices().first { it.name == "Home" }
        assertFalse(imported.enabled)
        assertTrue(imported.needsReview)
        dao.setAllEnabled(true)
        assertFalse(dao.getService(imported.id)!!.enabled)
    }
    @Test fun backupPickerKeepsPayloadAndRecordsSuccessOnlyAfterWriting() {
        runBlocking { dao.add(service) }
        model.prepareExport("long enough password".toCharArray(), true)
        waitWork()
        assertTrue(model.exportReady)
        model.pickerOpened()
        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://settings-tests/backup.json")
        shadowOf(app.contentResolver).registerOutputStream(uri, output)
        model.writeExport(uri); waitWork()
        assertTrue(output.size() > 0)
        assertTrue(AppSettings.prefs(app).getLong("last_backup", 0) > 0)
        val freshModel = SettingsViewModel(app, dao)
        freshModel.writeExport(uri)
        assertEquals("The export was interrupted. Please try again.", freshModel.message)
    }
    @Test fun pendingPickerGuardsPayloadAndCanceledOrFailedExportsDoNotRecordBackups() {
        model.prepareExport(charArrayOf(), false, template = true); waitWork()
        model.pickerOpened()
        val password = "long enough password".toCharArray()
        model.prepareExport(password, true)
        assertTrue(password.all { it == '\u0000' })
        assertEquals("template", model.exportKind)
        assertTrue(model.locked)
        model.writeExport(null)
        assertFalse(model.locked)
        assertEquals(0, AppSettings.prefs(app).getLong("last_backup", 0))
        model.prepareExport("long enough password".toCharArray(), true); waitWork(); model.pickerOpened()
        val uri = Uri.parse("content://settings-tests/failed-backup.json")
        shadowOf(app.contentResolver).registerOutputStream(uri, object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("storage full") }
        })
        model.writeExport(uri); waitWork()
        assertEquals(0, AppSettings.prefs(app).getLong("last_backup", 0))
        assertEquals("Couldn't save the file. Check the location and try again.", model.message)
    }
    @Test fun encryptedImportAsksForPasswordAfterReadingTheFileAndAllowsRetry() {
        val text = BackupCodec.encode(listOf(service), "long enough password".toCharArray()) { emptyMap() }
        val uri = Uri.parse("content://settings-tests/backup.json")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        model.readImport(uri); waitWork()
        assertTrue(model.passwordNeeded)
        model.unlockImport("wrong password".toCharArray()); waitWork()
        assertTrue(model.passwordNeeded)
        assertNull(model.bundle)
        model.unlockImport("long enough password".toCharArray()); waitWork()
        assertFalse(model.passwordNeeded)
        assertEquals(1, model.bundle!!.services.size)
    }
    @Test fun cleanupWorksWhilePausedAndKeepsLatestResultsAndIncidents() = runBlocking {
        val id = dao.add(service.copy(enabled = false))
        dao.record(CheckEntry(serviceId = id, time = 100, up = true, responseMs = 10, detail = "ok"))
        dao.record(CheckEntry(serviceId = id, time = 200, up = false, responseMs = 10, detail = "down"))
        dao.openIncident(Incident(serviceId = id, startedAt = 200, reason = "down"))
        assertEquals(1, dao.cleanupCount(1000))
        assertEquals(1, dao.cleanup(1000, System.currentTimeMillis()))
        assertEquals(200L, dao.latest(id)!!.time)
        assertEquals(1, dao.incidents(id).first().size)
        assertEquals(1, dao.storageStats().first().checks)
    }
    @Test fun quietHoursAndSnoozeKeepDeferredOutagesWithoutFalseRecovery() {
        val prefs = AppSettings.prefs(app)
        val quiet = AppSettings(quietHours = true)
        val zone = ZoneId.of("UTC")
        fun time(value: String) = Instant.parse("2026-11-15T${value}:00Z").toEpochMilli()
        assertTrue(AlertSilence.isQuiet(quiet, time("23:00"), zone))
        assertTrue(AlertSilence.isQuiet(quiet, time("06:59"), zone))
        assertFalse(AlertSilence.isQuiet(quiet, time("07:00"), zone))
        assertFalse(AlertSilence.isQuiet(quiet.copy(quietStart = 420, quietEnd = 420), time("07:00"), zone))
        prefs.edit().putLong("snooze_until", 2000).commit()
        assertNull(AlertSilence.event(prefs, 1, "is down", "DOWN", false, 1000))
        assertNull(AlertSilence.event(prefs, 1, null, "DOWN", true, 3000))
        assertNull(AlertSilence.event(prefs, 1, null, "DOWN", false, 3000, passed = true))
        assertEquals("is still down", AlertSilence.event(prefs, 1, null, "DOWN", false, 3000))
        assertNull(AlertSilence.event(prefs, 1, null, "DOWN", false, 4000))
        prefs.edit().putLong("snooze_until", 6000).commit()
        assertNull(AlertSilence.event(prefs, 2, "is down", "DOWN", false, 4500))
        assertNull(AlertSilence.event(prefs, 2, "is still down", "DOWN", false, 5500))
        assertNull(AlertSilence.event(prefs, 2, "recovered", "UP", false, 7000, passed = true))
        assertFalse(prefs.contains("deferred_alert_2"))
    }
    @Test fun backupRemindersWorkWithAllMonitorsPausedAndRespectBlockedChannels() {
        val prefs = AppSettings.prefs(app)
        val now = System.currentTimeMillis()
        AppSettings.write(prefs, AppSettings(backupReminderDays = 7))
        prefs.edit().putLong("backup_reminder_started", now - 8 * 86_400_000L).commit()
        AppNotifications.scheduleBackupReminder(app, now)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
        AppNotifications.remindBackup(app, now)
        assertEquals(now, prefs.getLong("last_backup_reminder", 0))
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.any { it.extras.getString("android.title") == "Time for a backup" })
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("backup")
        manager.createNotificationChannel(android.app.NotificationChannel("backup", "Backups", NotificationManager.IMPORTANCE_NONE))
        AppNotifications.remindBackup(app, now + 8 * 86_400_000L)
        assertEquals(now, prefs.getLong("last_backup_reminder", 0))
    }
    @Test fun discardedChecksDoNotReportAResultOrChangeHistory() = runBlocking {
        val id = dao.add(service)
        val saved = service.copy(id = id)
        val check = CheckEntry(serviceId = id, time = 100, up = true, responseMs = 10, detail = "ok")
        dao.update(saved.copy(enabled = false))
        assertNull(dao.recordResultIfUnchanged(saved, check))
        assertEquals(0, dao.storageStats().first().checks)
        dao.update(saved)
        val result = dao.recordResultIfUnchanged(saved, check)
        assertNotNull(result)
        assertNull(result!!.notification)
        assertEquals("UP", result.confirmed)
    }
    @Test fun supportReportLeavesOutMonitorAddressesAndCredentials() {
        runBlocking { dao.add(service) }
        model.makeReport(); waitWork()
        val report = model.report!!
        assertTrue(report.contains("androidApi"))
        assertFalse(report.contains("example.com"))
        assertFalse(report.contains("token"))
    }
    @Test fun versionFourMigrationPreservesMonitorsAndDefaultsToReviewed() = runBlocking {
        val name = "settings-migration.db"
        app.deleteDatabase(name)
        val schema = JsonParser.parseString(File("schemas/com.pocketupwatch.MonitorDb/4.json").readText()).asJsonObject["database"].asJsonObject
        val file = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            schema["entities"].asJsonArray.forEach { value ->
                val entity = value.asJsonObject
                sql.execSQL(entity["createSql"].asString.replace("\${TABLE_NAME}", entity["tableName"].asString))
                entity["indices"].asJsonArray.forEach { sql.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", entity["tableName"].asString)) }
            }
            sql.execSQL("INSERT INTO services (id,name,url,intervalSeconds,enabled,expectedCode,bodyType,expectedBody) VALUES (1,'Existing','https://example.com',300,1,0,'None','')")
            sql.version = 4
        }
        val migrated = Room.databaseBuilder(app, MonitorDb::class.java, name).addMigrations(MonitorDb.MIGRATION_4_5, MonitorDb.MIGRATION_5_6).allowMainThreadQueries().build()
        try { assertEquals("Existing", migrated.dao().getService(1)!!.name); assertFalse(migrated.dao().getService(1)!!.needsReview) }
        finally { migrated.close(); app.deleteDatabase(name) }
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val root = requireNotNull(view).rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/outputs/editor-preview/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
