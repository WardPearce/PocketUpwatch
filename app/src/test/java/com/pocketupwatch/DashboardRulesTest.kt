package com.pocketupwatch

import org.junit.Assert.*
import org.junit.Test

class DashboardRulesTest {
    private val now = 1_000_000L
    private val service = ServiceEntry(1, "Payments", "https://example.com/health?token=hidden", 300, failureThreshold = 3, recoveryThreshold = 2, tags = "production, api")
    private fun check(up: Boolean, skipped: Boolean = false) = CheckEntry(serviceId = 1, time = now, up = up, responseMs = 100, detail = "HTTP 503", skipped = skipped)

    @Test fun displayedHealthFollowsConfirmationInBothDirections() {
        val failing = AlertPolicy.evaluate(AlertState(1, confirmed = "UP"), service, check(false)).state
        assertEquals("UP", MonitorStatus.label(service, check(false), true, now, failing))
        assertEquals("Latest check failed · 1 of 3 failures", MonitorStatus.evidence(service, check(false), failing))
        val recovering = AlertPolicy.evaluate(AlertState(1, confirmed = "DOWN"), service, check(true)).state
        assertEquals("DOWN", MonitorStatus.label(service, check(true), true, now, recovering))
        assertEquals("Latest check passed · 1 of 2 successes to recover", MonitorStatus.evidence(service, check(true), recovering))
        val recovered = AlertPolicy.evaluate(recovering, service, check(true)).state
        assertEquals("UP", MonitorStatus.label(service, check(true), true, now, recovered))
    }

    @Test fun unknownAndOperationalStatesKeepTheLastConfirmedHealthWithoutClaimingRecovery() {
        val state = AlertState(1, confirmed = "DOWN")
        assertEquals("UNKNOWN", MonitorStatus.label(service, check(false, true), true, now, state))
        assertEquals("DOWN", MonitorStatus.confirmed(service, check(false, true), state))
        assertEquals("STALE", MonitorStatus.label(service, check(true), true, now + 601_000, state))
        assertEquals("PAUSED", MonitorStatus.label(service.copy(enabled = false), check(true), true, now, state))
        assertEquals("UNKNOWN", MonitorStatus.label(service, check(false), true, now, AlertState(1, downStreak = 1)))
    }

    @Test fun filteringCombinesExactTagsSearchAndConfirmedStatus() {
        val paused = service.copy(id = 2, name = "Staging", enabled = false, tags = "preproduction")
        val checks = mapOf(1L to check(false))
        val states = mapOf(1L to AlertState(1, confirmed = "UP", downStreak = 1))
        fun visible(filter: DashboardFilter, tag: String = "", query: String = "") = dashboardMonitors(listOf(service, paused), checks, states, true, now, query, tag, filter, "Name")
        assertTrue(visible(DashboardFilter.DOWN).isEmpty())
        assertEquals(listOf(service), visible(DashboardFilter.ALL, "PRODUCTION", "example.com"))
        assertEquals(listOf(paused), visible(DashboardFilter.PAUSED))
        assertTrue(visible(DashboardFilter.ALL, "prod").isEmpty())
        assertTrue(visible(DashboardFilter.ALL, query = "hidden").isEmpty())
    }

    @Test fun backgroundBannerExplainsStoppedDelayedAndRestrictedChecksAndClearsAfterRecovery() {
        fun health(active: Boolean = true, at: Long = now, notifications: Boolean = true, errorTime: Long = 0) =
            backgroundHealth(listOf(service), mapOf(1L to check(true)), active, at, now, errorTime, "Result save: IOException", notifications, true)
        assertEquals("Monitoring stopped", health(active = false)!!.title)
        assertEquals("Checks are delayed", health(at = now + 601_000)!!.title)
        assertEquals("Alerts are turned off", health(notifications = false)!!.title)
        assertEquals("Monitoring needs attention", health(errorTime = now + 1)!!.title)
        assertNull(health(errorTime = now - 1))
        assertNull(backgroundHealth(listOf(service.copy(enabled = false)), emptyMap(), false, now, 0, 0, null, false, false))
        assertNull(backgroundHealth(listOf(service.copy(maintenanceUntil = now + 900_000)), mapOf(1L to check(true)), true, now + 601_000, now, 0, null, true, true))
    }
}
