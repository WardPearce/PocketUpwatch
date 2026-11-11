package com.pocketupwatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

object CheckAlarm {
    const val ACTION_CHECK = "com.pocketupwatch.CHECK_ALARM"
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 0, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun schedule(context: Context, seconds: Int?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context)
        if (seconds == null) { alarms.cancel(operation); return }
        val trigger = SystemClock.elapsedRealtime() + seconds.coerceAtLeast(1) * 1000L
        try {
            if (canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Keep the CPU awake across delivery and service startup; the service releases its own lock after checking.
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketUpwatch:alarmDelivery")
        lock.acquire(10_000L)
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(CheckAlarm.ACTION_CHECK))
        } catch (e: Exception) {
            lock.release()
            // Background-start restrictions can reject delivery; opening the app restores monitoring.
        }
        // The bounded delivery lock bridges the asynchronous service startup.

    }
}
