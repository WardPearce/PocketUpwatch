package com.pocketupwatch

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceLifecycleTest {
    @Test fun noEnabledMonitorsStopsForegroundService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = MonitorDb.get(context).dao()
        runBlocking { dao.allServices().forEach { dao.delete(it) }; dao.add(ServiceEntry(name = "Paused", url = "https://example.com", intervalSeconds = 300, enabled = false)) }
        val controller = Robolectric.buildService(MonitorService::class.java).create().startCommand(0, 1)
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (MonitorService.active.value && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            assertFalse(MonitorService.active.value)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        } finally { controller.destroy() }
    }
}
