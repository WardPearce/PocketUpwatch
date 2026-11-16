package com.pocketupwatch

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.GsonBuilder

internal class SettingsViewModel @JvmOverloads constructor(app: Application, val dao: MonitorDao = MonitorDb.get(app).dao()) : AndroidViewModel(app) {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var exportReady by mutableStateOf(false); private set
    var exportKind by mutableStateOf("backup"); private set
    private var pickerOpen by mutableStateOf(false)
    val locked: Boolean get() = busy || exportReady || pickerOpen
    private var exportData: String? = null
    private var importData: String? = null
    var passwordNeeded by mutableStateOf(false); private set
    var bundle by mutableStateOf<BackupBundle?>(null); private set
    var selected by mutableStateOf<Set<Int>>(emptySet())
    var duplicates by mutableStateOf<Set<Int>>(emptySet()); private set
    var restoreSettings by mutableStateOf(false)
    var cleanupPreview by mutableStateOf<Long?>(null); private set
    private var cleanupBefore = 0L
    var report by mutableStateOf<String?>(null); private set
    fun closeReport() { report = null }

    private fun task(block: suspend () -> Unit) {
        if (locked) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Something went wrong. Please try again." }
            finally { busy = false }
        }
    }
    fun prepareExport(password: CharArray, includeSettings: Boolean, template: Boolean = false) {
        if (locked) { password.fill('\u0000'); return }
        task {
            try {
                exportData = withContext(Dispatchers.IO) { BackupCodec.encodeBundle(dao.allServices(), password,
                    if (includeSettings && !template) AppSettings.read(AppSettings.prefs(getApplication())) else null, SecretStore()::decrypt) }
                exportKind = if (template) "template" else "backup"
                exportReady = true
                message = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Couldn't prepare the file. Try again." }
            finally { password.fill('\u0000') }
        }
    }
    fun pickerOpened() { exportReady = false; pickerOpen = true }
    fun importPickerOpened() { if (!locked) pickerOpen = true }
    fun writeExport(uri: Uri?) {
        if (busy) return
        pickerOpen = false
        exportReady = false
        if (uri == null) { exportData = null; message = "Export canceled."; return }
        val data = exportData
        if (data == null) { message = "The export was interrupted. Please try again."; return }
        task {
            try {
                withContext(Dispatchers.IO) { requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(data) } }
                if (exportKind == "backup") {
                    AppSettings.prefs(getApplication()).edit().putLong("last_backup", System.currentTimeMillis()).apply()
                    AppNotifications.scheduleBackupReminder(getApplication())
                }
                message = when (exportKind) { "backup" -> "Backup saved."; "template" -> "Template saved."; else -> "Report saved." }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Couldn't save the file. Check the location and try again." }
            finally { exportData = null }
        }
    }
    fun readImport(uri: Uri?) {
        if (busy) return
        pickerOpen = false
        if (uri == null) return
        task {
            try {
                val data = withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (true) { val count = stream.read(bytes); if (count < 0) break; require(output.size() + count <= 8 * 1_024 * 1_024); output.write(bytes, 0, count) }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
                loadImport(data)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = if (e.message?.contains("Unsupported") == true) "This backup uses a newer format. Update the app and try again." else "Couldn't open this backup. Choose a Pocket Upwatch JSON file." }
        }
    }
    internal suspend fun loadImport(data: String) {
        importData = data
        if (BackupCodec.encrypted(data)) { passwordNeeded = true; message = null }
        else decodeImport(charArrayOf())
    }
    fun unlockImport(password: CharArray) {
        if (locked) { password.fill('\u0000'); return }
        task { decodeImport(password) }
    }
    private suspend fun decodeImport(password: CharArray) {
        try {
            val decoded = withContext(Dispatchers.IO) { BackupCodec.decodeBundle(requireNotNull(importData), password, SecretStore()::encrypt) }
            fun address(service: ServiceEntry) = "${service.method} ${displayTarget(service.url)}"
            val existing = dao.allServices().map(::address).toMutableSet()
            duplicates = decoded.services.mapIndexedNotNull { index, item -> if (!existing.add(address(item))) index else null }.toSet()
            selected = decoded.services.indices.filterNot { it in duplicates }.toSet()
            restoreSettings = false
            bundle = decoded
            passwordNeeded = false
            message = null
            importData = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            message = when {
                e is javax.crypto.AEADBadTagException -> "The password is incorrect, or the file has changed. Try again."
                e.message?.contains("Unsupported") == true -> "This backup uses a newer format. Update the app and try again."
                else -> "This backup contains invalid or incomplete data. Choose another file."
            }
        } finally { password.fill('\u0000') }
    }
    fun cancelImport() { if (!busy) { bundle = null; passwordNeeded = false; importData = null; selected = emptySet(); message = null } }
    fun importSelected() {
        val current = bundle ?: return
        val rows = selected.sorted().mapNotNull { current.services.getOrNull(it) }
        val preferences = current.settings.takeIf { restoreSettings }
        if (rows.isEmpty() && preferences == null) return
        task {
            dao.importSelection(rows)
            // Clear the preview as soon as the transaction succeeds, so a preference-write error cannot reimport rows.
            bundle = null
            selected = emptySet()
            message = when (rows.size) {
                0 -> "App preferences restored."
                1 -> "Imported 1 monitor. It's paused until you're ready."
                else -> "Imported ${rows.size} monitors. They're paused until you're ready."
            }
            if (preferences != null) {
                try { AppSettings.write(AppSettings.prefs(getApplication()), preferences); AppNotifications.scheduleBackupReminder(getApplication()); refreshAlertReminders() }
                catch (_: Exception) { message = "Monitors imported. App settings couldn't be restored." }
            }
        }
    }
    fun refreshAlertReminders() {
        viewModelScope.launch {
            try { AlertReminderAlarm.reconcile(getApplication(), dao) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { BackgroundDiagnostics.failure(getApplication(), "Reminder scheduling", e) }
        }
    }
    fun previewCleanup(days: Int) = task {
        cleanupBefore = System.currentTimeMillis() - days * 86_400_000L
        cleanupPreview = dao.cleanupCount(cleanupBefore)
    }
    fun cancelCleanup() { if (!busy) cleanupPreview = null }
    fun cleanUp() {
        if (cleanupPreview == null) return
        task {
            val count = dao.cleanup(cleanupBefore, System.currentTimeMillis())
            AppSettings.prefs(getApplication()).edit().putLong("last_cleanup", System.currentTimeMillis()).apply()
            cleanupPreview = null
            message = "Removed $count old checks. Each monitor's latest result was kept."
        }
    }
    fun makeReport() = task {
        val context = getApplication<Application>()
        val details = BackgroundDiagnostics.prefs(context).all.filterKeys { it in listOf("last_alarm", "last_cycle", "last_result", "last_error_time", "last_error") }
        val values = linkedMapOf<String, Any?>("appVersion" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
            "androidApi" to Build.VERSION.SDK_INT, "monitoringActive" to MonitorService.active.value,
            "exactAlarms" to CheckAlarm.canScheduleExact(context),
            "unrestrictedBattery" to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            "serviceAlerts" to AppNotifications.state(context, "alerts"), "monitoringNotifications" to AppNotifications.state(context, "monitor"),
            "background" to details)
        report = GsonBuilder().setPrettyPrinting().create().toJson(values)
    }
    fun exportReport() { if (locked) return; val data = report ?: return; exportData = data; exportKind = "report"; exportReady = true }
}

internal fun settingsTime(time: Long): String = if (time == 0L) "Never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
