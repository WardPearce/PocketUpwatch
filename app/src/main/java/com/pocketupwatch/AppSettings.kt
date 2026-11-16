package com.pocketupwatch

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.Instant
import java.time.ZoneId

/** Portable preferences never contain passwords, Android grants, widget IDs, or running state. */
internal data class AppSettings(
    val theme: String = "System", val retentionDays: Int = 90,
    val sort: String = "Newest", val chartHours: Int = 24, val compact: Boolean = false,
    val absoluteTimes: Boolean = false, val intervalSeconds: Int = 300, val timeoutSeconds: Int = 20,
    val requireInternet: Boolean = true, val failures: Int = 1, val recoveries: Int = 1,
    val backupReminderDays: Int = 0, val quietHours: Boolean = false,
    val quietStart: Int = 22 * 60, val quietEnd: Int = 7 * 60
) {
    fun validate(): AppSettings {
        require(theme in listOf("System", "Light", "Dark"))
        require(retentionDays in listOf(30, 90, 180, 365))
        require(sort in listOf("Newest", "Name", "Status", "Paused first", "Tag"))
        require(chartHours in listOf(1, 24, 168, 720))
        require(intervalSeconds in 300..86400 && timeoutSeconds in 1..60)
        require(failures in 1..20 && recoveries in 1..20)
        require(backupReminderDays in listOf(0, 7, 30, 90))
        require(quietStart in 0..1439 && quietEnd in 0..1439)
        return this
    }
    fun json(): JsonObject = Gson().toJsonTree(this).asJsonObject
    companion object {
        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        fun read(p: SharedPreferences): AppSettings = AppSettings(
            theme = p.getString("theme", "System") ?: "System", retentionDays = p.getInt("retention_days", 90),
            sort = p.getString("default_sort", "Newest") ?: "Newest", chartHours = p.getInt("chart_hours", 24),
            compact = p.getBoolean("compact", false), absoluteTimes = p.getBoolean("absolute_times", false),
            intervalSeconds = p.getInt("default_interval", 300), timeoutSeconds = p.getInt("default_timeout", 20),
            requireInternet = p.getBoolean("default_internet", true), failures = p.getInt("default_failures", 1), recoveries = p.getInt("default_recoveries", 1),
            backupReminderDays = p.getInt("backup_reminder_days", 0), quietHours = p.getBoolean("quiet_hours", false),
            quietStart = p.getInt("quiet_start", 1320), quietEnd = p.getInt("quiet_end", 420)
        )
        fun write(p: SharedPreferences, value: AppSettings) {
            val s = value.validate()
            if (s.backupReminderDays > 0 && p.getInt("backup_reminder_days", 0) == 0) p.edit().putLong("backup_reminder_started", System.currentTimeMillis()).apply()
            check(p.edit().putString("theme", s.theme).putInt("retention_days", s.retentionDays)
                .putString("default_sort", s.sort).putInt("chart_hours", s.chartHours).putBoolean("compact", s.compact)
                .putBoolean("absolute_times", s.absoluteTimes).putInt("default_interval", s.intervalSeconds)
                .putInt("default_timeout", s.timeoutSeconds).putBoolean("default_internet", s.requireInternet)
                .putInt("default_failures", s.failures).putInt("default_recoveries", s.recoveries)
                .putInt("backup_reminder_days", s.backupReminderDays).putBoolean("quiet_hours", s.quietHours)
                .putInt("quiet_start", s.quietStart).putInt("quiet_end", s.quietEnd).commit())
        }
        fun fromJson(value: JsonObject): AppSettings {
            val defaults = AppSettings().json()
            value.entrySet().forEach { (key, item) ->
                if (defaults.has(key)) {
                    val expected = defaults[key].asJsonPrimitive
                    require(item.isJsonPrimitive)
                    val actual = item.asJsonPrimitive
                    require(expected.isBoolean && actual.isBoolean || expected.isNumber && actual.isNumber && actual.asString.matches(Regex("-?[0-9]+")) || expected.isString && actual.isString)
                    if (expected.isNumber) require(actual.asString.toLongOrNull() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                    defaults.add(key, item)
                }
            }
            return Gson().fromJson(defaults, AppSettings::class.java).validate()
        }
    }
}

@Composable internal fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current
    val prefs = remember(context) { AppSettings.prefs(context) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(version) { AppSettings.read(prefs) }
}

internal object AlertSilence {
    fun isQuiet(settings: AppSettings, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!settings.quietHours || settings.quietStart == settings.quietEnd) return false
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val minute = local.hour * 60 + local.minute
        return if (settings.quietStart < settings.quietEnd) minute in settings.quietStart until settings.quietEnd
        else minute >= settings.quietStart || minute < settings.quietEnd
    }
    fun suppressed(prefs: SharedPreferences, now: Long) = prefs.getLong("snooze_until", 0) > now || isQuiet(AppSettings.read(prefs), now)

    fun nextAllowedTime(prefs: SharedPreferences, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val next = maxOf(now, prefs.getLong("snooze_until", 0))
        val settings = AppSettings.read(prefs)
        if (!isQuiet(settings, next, zone)) return next
        val date = Instant.ofEpochMilli(next).atZone(zone).toLocalDate()
        // Consider both offsets when quiet hours end during a repeated DST hour.
        return (0L..2L).flatMap { days ->
            val localEnd = date.plusDays(days).atTime(settings.quietEnd / 60, settings.quietEnd % 60)
            val end = localEnd.atZone(zone)
            val gap = zone.rules.getTransition(localEnd)?.takeIf { it.isGap }?.instant?.toEpochMilli()
            listOfNotNull(end.withEarlierOffsetAtOverlap().toInstant().toEpochMilli(), end.withLaterOffsetAtOverlap().toInstant().toEpochMilli(), gap)
        }.filter { it > next && !isQuiet(settings, it, zone) }.minOrNull() ?: next + 86_400_000L
    }

    /** An outage still open after quiet hours is announced on its next failed check. */
    fun event(prefs: SharedPreferences, id: Long, event: String?, confirmed: String, skipped: Boolean, now: Long, passed: Boolean = false): String? {
        if (skipped) return null
        val key = "deferred_alert_$id"
        if (suppressed(prefs, now)) {
            if (event != null && confirmed == "DOWN" && prefs.getString(key, null) != "is down") prefs.edit().putString(key, event).apply()
            if (confirmed == "UP") prefs.edit().remove(key).apply()
            return null
        }
        val deferred = prefs.getString(key, null)
        if (passed && confirmed == "DOWN") return null
        prefs.edit().remove(key).apply()
        if (deferred == "is down" && event == "recovered") return null
        return event ?: if (deferred != null && confirmed == "DOWN" && !passed) "is still down" else null
    }
}
