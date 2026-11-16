package com.pocketupwatch

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceLifecycleTest {
    @Test fun backgroundCycleStoresSkippedCheckSchedulesNextAlarmAndReleasesCpu() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // This test exercises an offline skip, never a real request or a host network default.
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertNull(connectivity.activeNetwork)
        val dao = MonitorDb.get(context).dao()
        val id = runBlocking {
            dao.allServices().forEach { dao.delete(it) }
            dao.add(ServiceEntry(name = "Background", url = "https://example.com", intervalSeconds = 300))
        }
        val controller = Robolectric.buildService(MonitorService::class.java).create().startCommand(0, 1)
        try {
            await("an offline check and an idle monitoring service", context) {
                runBlocking { dao.latest(id) } != null && MonitorService.checksInProgress.value.isEmpty()
                    && ShadowPowerManager.getLatestWakeLock()?.isHeld != true
            }
            val latest = runBlocking { dao.latest(id) }!!
            assertTrue(latest.skipped)
            assertEquals("No network connection", latest.detail)
            assertTrue(BackgroundDiagnostics.prefs(context).getLong("last_result", 0) > 0)
            assertTrue(shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.any {
                shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name
            })
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally { destroyAndAwait(controller, context) }
    }
    @Test fun noEnabledMonitorsStopsForegroundService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = MonitorDb.get(context).dao()
        runBlocking { dao.allServices().forEach { dao.delete(it) }; dao.add(ServiceEntry(name = "Paused", url = "https://example.com", intervalSeconds = 300, enabled = false)) }
        MonitorService.checksInProgress.value = setOf(123)
        val controller = Robolectric.buildService(MonitorService::class.java).create().startCommand(0, 1)
        try {
            await("the service to stop itself", context) { !MonitorService.active.value }
            assertTrue(MonitorService.checksInProgress.value.isEmpty())
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        } finally { destroyAndAwait(controller, context) }
    }
    private fun destroyAndAwait(controller: ServiceController<MonitorService>, context: Context) {
        val job = ReflectionHelpers.getField<CoroutineScope>(controller.get(), "scope").coroutineContext.job
        controller.destroy()
        // Cancellation can finish after onDestroy returns, while Room resumes on the main looper.
        // Drain it before Robolectric resets the looper, so a scheduling task cannot retain its mutex.
        await("all service coroutines to finish cancellation", context) { job.isCompleted }
    }
    private fun await(description: String, context: Context, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for $description; background error: ${BackgroundDiagnostics.prefs(context).getString("last_error", "none")}")
    }
}
