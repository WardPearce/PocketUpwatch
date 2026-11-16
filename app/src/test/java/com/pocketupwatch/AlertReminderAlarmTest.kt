package com.pocketupwatch

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlertReminderAlarmTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, MonitorDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val now = System.currentTimeMillis()
    private val zone = TimeZone.getDefault()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowAlarmManager.setAutoSchedule(false)
        AppSettings.prefs(app).edit().clear().commit()
        shadowOf(manager).setNotificationsEnabled(true)
        if (android.os.Build.VERSION.SDK_INT >= 33) shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AppNotifications.channels(app)
    }
    @After fun close() {
        AlertReminderAlarm.arm(app, null)
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(zone)
    }
    private suspend fun outage(minutes: Int = 5, at: Long = now): ServiceEntry {
        val id = dao.add(ServiceEntry(name = "API", url = "https://example.com", intervalSeconds = 86400, repeatMinutes = minutes))
        val service = dao.getService(id)!!
        dao.recordResultIfUnchanged(service, CheckEntry(serviceId = id, time = at, up = false, responseMs = 10, detail = "HTTP 503"))
        return service
    }
    private fun reminderAlarms() = alarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == AlertReminderAlarm.ACTION_REMINDER }
    private fun nextAlarm() = reminderAlarms().single()

    @Test fun alarmDeliversBetweenChecksAndRearmsWithoutAddingHistory() = runBlocking {
        val service = outage()
        AlertReminderAlarm.reconcile(app, dao, now)
        assertEquals(AlarmManager.RTC_WAKEUP, nextAlarm().type)
        assertTrue(nextAlarm().allowWhileIdle)
        assertEquals(now + 300_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.deliver(app, dao, now + 299_999)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        val notification = shadowOf(manager).allNotifications.single()
        assertTrue(notification.extras.getString("android.title")!!.contains("outage reminder"))
        assertTrue(notification.extras.getString("android.text")!!.contains("Last failed check"))
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(1, dao.history(service.id).first().size)
        assertEquals(now, dao.latest(service.id)!!.time)
        assertEquals(now + 300_000, dao.alertState(service.id)!!.lastAlertAt)
        assertEquals(now + 600_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        assertEquals(now + 300_000, dao.alertState(service.id)!!.lastAlertAt)
        assertEquals(now + 600_000, nextAlarm().triggerAtTime)
    }
    @Test fun duplicateAlarmClaimsAreTransactional() = runBlocking {
        val service = outage()
        val claimed = (1..8).map { async(Dispatchers.Default) { dao.claimReminder(service.id, now + 300_000) } }.awaitAll()
        assertEquals(1, claimed.count { it != null })
    }
    @Test fun recoveryCancelsTheAlarmAndStaleDeliveryDoesNothing() = runBlocking {
        val service = outage()
        AlertReminderAlarm.reconcile(app, dao, now)
        AlertReminderAlarm.update(app, dao) {
            dao.recordResultIfUnchanged(service, CheckEntry(serviceId = service.id, time = now + 1_000, up = true, responseMs = 10, detail = "HTTP 200"))
        }
        assertTrue(reminderAlarms().isEmpty())
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun pauseDisableMaintenanceAndDeleteAreRecheckedAtDelivery() = runBlocking {
        val service = outage()
        AlertReminderAlarm.reconcile(app, dao, now)
        AlertReminderAlarm.update(app, dao) { dao.update(service.copy(enabled = false)) }
        assertTrue(reminderAlarms().isEmpty())
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        AlertReminderAlarm.update(app, dao) { dao.update(service.copy(repeatMinutes = 0)) }
        assertTrue(reminderAlarms().isEmpty())
        AlertReminderAlarm.update(app, dao) { dao.update(service.copy(maintenanceUntil = now + 3_600_000)) }
        assertEquals(now + 3_600_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        AlertReminderAlarm.update(app, dao) { dao.delete(service) }
        assertTrue(reminderAlarms().isEmpty())
    }
    @Test fun unknownAndPendingRecoveryChecksPauseReminders() = runBlocking {
        val service = outage()
        dao.recordResultIfUnchanged(service, CheckEntry(serviceId = service.id, time = now + 1_000, up = false, responseMs = 0, detail = "No network", skipped = true))
        AlertReminderAlarm.reconcile(app, dao, now + 1_000)
        assertTrue(reminderAlarms().isEmpty())
        val recovering = service.copy(recoveryThreshold = 2)
        dao.update(recovering)
        dao.recordResultIfUnchanged(recovering, CheckEntry(serviceId = service.id, time = now + 2_000, up = true, responseMs = 1, detail = "HTTP 200"))
        assertEquals("DOWN", dao.alertState(service.id)!!.confirmed)
        AlertReminderAlarm.reconcile(app, dao, now + 2_000)
        assertTrue(reminderAlarms().isEmpty())
        dao.recordResultIfUnchanged(recovering, CheckEntry(serviceId = service.id, time = now + 3_000, up = false, responseMs = 1, detail = "HTTP 503"))
        AlertReminderAlarm.reconcile(app, dao, now + 3_000)
        assertEquals(now + 300_000, nextAlarm().triggerAtTime)
    }
    @Test fun quietHoursAndSnoozeDeferRatherThanConsumeAReminder() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val start = Instant.parse("2026-11-15T23:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-16T07:00:00Z").toEpochMilli()
        val service = outage(at = start)
        val prefs = AppSettings.prefs(app)
        AppSettings.write(prefs, AppSettings(quietHours = true))
        prefs.edit().putLong("snooze_until", end + 600_000).putString("deferred_alert_${service.id}", "is down").commit()
        AlertReminderAlarm.reconcile(app, dao, start)
        assertEquals(end + 600_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.deliver(app, dao, start + 300_000)
        assertEquals(start, dao.alertState(service.id)!!.lastAlertAt)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        AlertReminderAlarm.deliver(app, dao, end + 600_000)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertFalse(prefs.contains("deferred_alert_${service.id}"))
    }
    @Test fun blockedNotificationsRetryWithoutAdvancingTheLastAlert() = runBlocking {
        val service = outage()
        shadowOf(manager).setNotificationsEnabled(false)
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        assertEquals(now, dao.alertState(service.id)!!.lastAlertAt)
        assertEquals(now + 600_000, nextAlarm().triggerAtTime)
        shadowOf(manager).setNotificationsEnabled(true)
        AlertReminderAlarm.deliver(app, dao, now + 600_000)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
    @Test fun onlyTheEarliestOfMultipleServicesUsesAnAlarmAndRestoreRearmsIt() = runBlocking {
        val first = outage(minutes = 10)
        outage(minutes = 5)
        AlertReminderAlarm.reconcile(app, dao, now)
        assertEquals(1, reminderAlarms().size)
        assertEquals(now + 300_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.arm(app, null)
        AlertReminderAlarm.reconcile(app, dao, now)
        assertEquals(now + 300_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.deliver(app, dao, now + 300_000)
        assertEquals(now, dao.alertState(first.id)!!.lastAlertAt)
        assertEquals(now + 600_000, nextAlarm().triggerAtTime)
    }
    @Test @Config(sdk = [35]) fun exactAlarmsUseThePermissionWithAnInexactFallback() = runBlocking {
        outage()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AlertReminderAlarm.reconcile(app, dao, now)
        assertNotEquals(0L, nextAlarm().windowLengthMs)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlertReminderAlarm.reconcile(app, dao, now)
        assertEquals(0L, nextAlarm().windowLengthMs)
    }
    @Test fun backwardClockChangesRebaseOnceAndKeepAStableDeadline() = runBlocking {
        val service = outage()
        val earlier = now - 3_600_000
        AlertReminderAlarm.reconcile(app, dao, earlier)
        assertEquals(earlier, dao.alertState(service.id)!!.lastAlertAt)
        assertEquals(earlier + 300_000, nextAlarm().triggerAtTime)
        AlertReminderAlarm.reconcile(app, dao, earlier + 60_000)
        assertEquals(earlier + 300_000, nextAlarm().triggerAtTime)
    }
    @Test fun quietHoursEndCorrectlyThroughDstGapsAndOverlaps() {
        val prefs = AppSettings.prefs(app)
        AppSettings.write(prefs, AppSettings(quietHours = true, quietStart = 23 * 60, quietEnd = 90))
        val zone = ZoneId.of("America/New_York")
        val repeatedHour = Instant.parse("2026-11-01T06:15:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), AlertSilence.nextAllowedTime(prefs, repeatedHour, zone))
        AppSettings.write(prefs, AppSettings(quietHours = true, quietStart = 23 * 60, quietEnd = 150))
        val spring = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), AlertSilence.nextAllowedTime(prefs, spring, zone))
    }
    @Test fun alarmPendingIntentRoutesThroughTheRealReceiver() = runBlocking {
        val persistent = MonitorDb.get(app).dao()
        val id = persistent.add(ServiceEntry(name = "Receiver API", url = "https://example.com", intervalSeconds = 86400, repeatMinutes = 5))
        val service = persistent.getService(id)!!
        persistent.recordResultIfUnchanged(service, CheckEntry(serviceId = id, time = now - 300_000, up = false, responseMs = 1, detail = "HTTP 503"))
        try {
            AlertReminderAlarm.reconcile(app, persistent, now)
            alarms.fireAlarm(nextAlarm())
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(6_000) { while (shadowOf(manager).allNotifications.isEmpty() || ShadowPowerManager.getLatestWakeLock()?.isHeld == true) delay(10) }
            assertTrue(shadowOf(manager).allNotifications.any { it.extras.getString("android.title")!!.contains("Receiver API") })
            assertTrue(persistent.alertState(id)!!.lastAlertAt >= now)
        } finally { persistent.delete(service); AlertReminderAlarm.reconcile(app, persistent) }
    }
}
