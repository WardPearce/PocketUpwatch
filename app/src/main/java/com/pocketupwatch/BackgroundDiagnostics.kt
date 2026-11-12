package com.pocketupwatch

import android.content.Context
import android.os.PowerManager

internal suspend fun <T> withBackgroundWakeLock(context: Context, tag: String, timeoutMs: Long, block: suspend () -> T): T {
    val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketUpwatch:$tag")
    lock.acquire(timeoutMs)
    try { return block() }
    finally { if (lock.isHeld) lock.release() }
}

internal object BackgroundDiagnostics {
    fun prefs(context: Context) = context.getSharedPreferences("background_diagnostics", Context.MODE_PRIVATE)
    fun mark(context: Context, key: String) { prefs(context).edit().putLong(key, System.currentTimeMillis()).apply() }
    fun failure(context: Context, stage: String, error: Exception) {
        // Exception messages may contain target URLs or credentials; retain only the category.
        prefs(context).edit().putString("last_error", "$stage: ${error.javaClass.simpleName}")
            .putLong("last_error_time", System.currentTimeMillis()).apply()
    }
}
