package com.pocketupwatch

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundMonitoringTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun wakeLockSpansSuspendingPersistenceAndIsReleasedAfterFailure() = runBlocking {
        var heldAfterSuspend = false
        assertTrue(runCatching {
            withBackgroundWakeLock(context, "test", 30_000) {
                yield()
                heldAfterSuspend = ShadowPowerManager.getLatestWakeLock().isHeld
                throw IllegalStateException("storage unavailable")
            }
        }.isFailure)
        assertTrue(heldAfterSuspend)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun cancellationReleasesTheBackgroundWakeLock() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withBackgroundWakeLock(context, "cancel", 30_000) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        job.cancelAndJoin()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun rejectedAlarmRestartIsRecordedWithoutLeakingExceptionMessages() {
        val denied = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? { throw IllegalStateException("https://secret:password@example.com") }
        }
        AlarmReceiver().onReceive(denied, Intent(CheckAlarm.ACTION_CHECK))
        val diagnostics = BackgroundDiagnostics.prefs(context)
        assertTrue(diagnostics.getLong("last_alarm", 0) > 0)
        assertEquals("Background restart blocked: IllegalStateException", diagnostics.getString("last_error", null))
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun reopeningExistingDatabaseUpgradesShortIntervalsWithoutChangingOtherSettings() = runBlocking {
        val name = "short-interval.db"
        context.deleteDatabase(name)
        val old = Room.databaseBuilder(context, MonitorDb::class.java, name).allowMainThreadQueries().build()
        val id = old.dao().add(ServiceEntry(name = "Local", url = "http://192.168.1.1", intervalSeconds = 60, enabled = false, requireValidatedInternet = false))
        old.close()
        val upgraded = Room.databaseBuilder(context, MonitorDb::class.java, name).allowMainThreadQueries().addCallback(MonitorDb.MINIMUM_INTERVAL_CALLBACK).build()
        try {
            val service = upgraded.dao().getService(id)!!
            assertEquals(300, service.intervalSeconds)
            assertFalse(service.enabled)
            assertFalse(service.requireValidatedInternet)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
