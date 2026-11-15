package com.pocketupwatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal object AppNotifications {
    fun scheduleBackupReminder(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = AppSettings.prefs(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, -12, Intent(context, BackupReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val days = AppSettings.read(prefs).backupReminderDays
        if (days == 0) { alarm.cancel(intent); return }
        val last = maxOf(prefs.getLong("last_backup", 0), prefs.getLong("backup_reminder_started", now), prefs.getLong("last_backup_reminder", 0))
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(now + 3_600_000, last + days * 86_400_000L), intent)
    }
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitor", "Monitoring status", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("alerts", "Service alerts", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("backup", "Backup reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun allowed(context: Context, channel: String): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    fun state(context: Context, channel: String): String {
        if (!allowed(context, channel)) return "Blocked"
        val importance = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance
        return if (importance != null && importance < NotificationManager.IMPORTANCE_DEFAULT) "Silent" else "Allowed"
    }
    fun test(context: Context): String {
        channels(context)
        if (!allowed(context, "alerts")) return "Allow service alerts in Android settings first."
        context.getSystemService(NotificationManager::class.java).notify(-10, NotificationCompat.Builder(context, "alerts")
            .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Test alert")
            .setContentText("Service alerts are working.").setAutoCancel(true).build())
        return "Test alert sent."
    }
    fun remindBackup(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = AppSettings.prefs(context)
        val days = AppSettings.read(prefs).backupReminderDays
        if (days == 0 || AlertSilence.suppressed(prefs, now)) return
        val last = maxOf(prefs.getLong("last_backup", 0), prefs.getLong("backup_reminder_started", now), prefs.getLong("last_backup_reminder", 0))
        if (now - last < days * 86_400_000L) return
        channels(context)
        if (!allowed(context, "backup")) return
        val intent = Intent(context, MainActivity::class.java).putExtra("settingsPage", "Backup and restore")
        val pending = PendingIntent.getActivity(context, -11, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(NotificationManager::class.java).notify(-11, NotificationCompat.Builder(context, "backup")
            .setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("Time for a backup")
            .setContentText("Save a copy of your monitors and settings.").setContentIntent(pending).setAutoCancel(true).build())
        prefs.edit().putLong("last_backup_reminder", now).apply()
    }
}

class BackupReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppNotifications.remindBackup(context)
        AppNotifications.scheduleBackupReminder(context)
    }
}
