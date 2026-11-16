package com.pocketupwatch

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date

data class ReminderSnapshot(val service: ServiceEntry, val state: AlertState?, val latest: CheckEntry?)

internal object ReminderPolicy {
    fun nextAt(snapshot: ReminderSnapshot): Long? {
        val (service, state, latest) = snapshot
        if (!service.enabled || service.needsReview || service.repeatMinutes <= 0 || state?.confirmed != "DOWN" || latest == null || latest.skipped || latest.up) return null
        return maxOf(state.lastAlertAt + AlertReminders.normalize(service.repeatMinutes) * 60_000L, service.maintenanceUntil)
    }
}

/** One alarm tracks the earliest outstanding reminder, independently of network checks. */
internal object AlertReminderAlarm {
    const val ACTION_REMINDER = "com.pocketupwatch.ALERT_REMINDER"
    private const val RETRY_MS = 5 * 60_000L
    private val lock = Mutex()
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, -13,
        Intent(context, AlertReminderReceiver::class.java).setAction(ACTION_REMINDER), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun arm(context: Context, at: Long?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context)
        if (at == null) { manager.cancel(operation); return }
        try {
            if (CheckAlarm.canScheduleExact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation) }
    }
    fun retry(context: Context) = arm(context, System.currentTimeMillis() + RETRY_MS)
    suspend fun reconcile(context: Context, dao: MonitorDao, now: Long = System.currentTimeMillis()) = lock.withLock { scheduleLocked(context, dao, now) }

    /** Serialize check/configuration changes and notification delivery, including recovery and deletion. */
    suspend fun <T> update(context: Context, dao: MonitorDao, block: suspend () -> T): T = lock.withLock {
        try { block() }
        finally {
            try { scheduleLocked(context, dao, System.currentTimeMillis()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { BackgroundDiagnostics.failure(context, "Reminder scheduling", e) }
        }
    }
    private suspend fun scheduleLocked(context: Context, dao: MonitorDao, now: Long, retryAfter: Long = now) {
        // A backward clock adjustment must not leave persisted timestamps in the future indefinitely.
        dao.rebaseFutureAlertTimes(now)
        val next = dao.reminderSnapshots().mapNotNull(ReminderPolicy::nextAt).minOrNull()
        if (next == null) { arm(context, null); return }
        val prefs = AppSettings.prefs(context)
        val permittedAt = AlertSilence.nextAllowedTime(prefs, maxOf(now, next))
        val notificationRetry = if (AppNotifications.allowed(context, "alerts")) now else now + RETRY_MS
        arm(context, maxOf(now + 1_000, permittedAt, notificationRetry, retryAfter))
    }
    suspend fun deliver(context: Context, dao: MonitorDao, now: Long = System.currentTimeMillis()) = lock.withLock {
        AppNotifications.channels(context)
        val prefs = AppSettings.prefs(context)
        var failed = false
        if (!AlertSilence.suppressed(prefs, now) && AppNotifications.allowed(context, "alerts")) {
            dao.reminderSnapshots().filter { ReminderPolicy.nextAt(it)?.let { due -> due <= now } == true }.forEach { snapshot ->
                val claimed = dao.claimReminder(snapshot.service.id, now) ?: return@forEach
                val posted = try {
                    withContext(Dispatchers.Main.immediate) {
                        if (AlertSilence.suppressed(prefs, now) || !AppNotifications.allowed(context, "alerts")) false
                        else {
                            val id = claimed.service.id
                            val open = PendingIntent.getActivity(context, (id % Int.MAX_VALUE).toInt(),
                                Intent(context, MainActivity::class.java).putExtra("serviceId", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val checkedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(claimed.latest!!.time))
                            val notification = NotificationCompat.Builder(context, "alerts")
                                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("${claimed.service.name} · outage reminder")
                                .setContentText("Last failed check: $checkedAt · ${claimed.latest.detail}")
                                .setContentIntent(open).setAutoCancel(true).setWhen(now).build()
                            context.getSystemService(NotificationManager::class.java).notify((id % (Int.MAX_VALUE - 2)).toInt() + 2, notification)
                            prefs.edit().remove("deferred_alert_$id").apply()
                            true
                        }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { dao.restoreReminderTime(claimed.service.id, now, snapshot.state!!.lastAlertAt) }
                    throw e
                }
                catch (e: Exception) { BackgroundDiagnostics.failure(context, "Reminder delivery", e); false }
                if (!posted) { failed = true; dao.restoreReminderTime(claimed.service.id, now, snapshot.state!!.lastAlertAt) }
            }
        }
        scheduleLocked(context, dao, now, if (failed) now + RETRY_MS else now)
    }
}

class AlertReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketUpwatch:alertReminder")
        wake.acquire(10_000L)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val dao = MonitorDb.get(app).dao()
                    if (intent.action == AlertReminderAlarm.ACTION_REMINDER) AlertReminderAlarm.deliver(app, dao)
                    else AlertReminderAlarm.reconcile(app, dao)
                }
            } catch (e: Exception) {
                BackgroundDiagnostics.failure(app, "Alert reminder", e)
                runCatching { AlertReminderAlarm.retry(app) }
            } finally {
                if (wake.isHeld) wake.release()
                pending.finish()
            }
        }
    }
}
