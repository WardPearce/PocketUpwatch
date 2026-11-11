package com.pocketupwatch

import org.junit.Assert.*
import org.junit.Test

class MonitoringRulesTest {
    private val service = ServiceEntry(id = 1, name = "API", url = "https://example.com", intervalSeconds = 300, failureThreshold = 2, requireValidatedInternet = false)
    private fun check(up: Boolean, time: Long = 1_000, skipped: Boolean = false) = CheckEntry(serviceId = 1, time = time, up = up, responseMs = 10, detail = "test", skipped = skipped)
    @Test fun newMonitorsAreDownAfterOneFailedCheckByDefault() {
        val monitor = ServiceEntry(id = 1, name = "API", url = "https://example.com", intervalSeconds = 300)
        assertEquals(1, monitor.failureThreshold)
        val decision = AlertPolicy.evaluate(AlertState(1, confirmed = "UP"), monitor, check(false))
        assertEquals("DOWN", decision.state.confirmed)
        assertEquals("is down", decision.notification)
    }
    @Test fun jsonComparisonIsStructuralAndTypeAware() {
        assertTrue(BodyMatcher.matches("JSON", "{\"b\":2,\"a\":[1,{\"ok\":true}]}", "{\"a\":[1,{\"ok\":true}],\"b\":2}"))
        assertFalse(BodyMatcher.matches("JSON", "\"1\"", "1"))
        assertFalse(BodyMatcher.matches("JSON", "\"true\"", "true"))
        assertFalse(BodyMatcher.matches("JSON", "[1,2]", "[2,1]"))
        assertFalse(BodyMatcher.matches("JSON", "{\"a\":1}", "{\"a\":1} invalid"))
        assertFalse(BodyMatcher.matches("JSON", "{\"a\":1}", "{'a':1}"))
        assertTrue(BodyMatcher.matches("JSON", "null", "null"))
        assertTrue(BodyMatcher.matches("JSON", "1.0", "1"))
    }
    @Test fun pathsHandleNestedObjectsArraysAndMissingValues() {
        assertTrue(BodyMatcher.matches("JSON", "\"ok\"", "{\"items\":[{\"status\":\"ok\"}]}", "$.items[0].status"))
        assertFalse(BodyMatcher.matches("JSON", "true", "{\"ok\":true}", "$.missing"))
        assertNotNull(BodyMatcher.validate("JSON", "{}", "$.items[*]"))
        assertNotNull(BodyMatcher.validate("JSON", "not json"))
    }
    @Test fun xmlRejectsExternalEntitiesAndTextIsExact() {
        assertTrue(BodyMatcher.matches("XML", "<ok/>", "<ok/>"))
        assertFalse(BodyMatcher.matches("XML", "<ok/>", "<!DOCTYPE ok [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><ok>&x;</ok>"))
        assertFalse(BodyMatcher.matches("Text", "ok", "ok\n"))
    }
    @Test fun thresholdsPreventFlappingAndUnknownBreaksStreak() {
        val first = AlertPolicy.evaluate(AlertState(1, confirmed = "UP"), service, check(false))
        assertEquals("UP", first.state.confirmed)
        assertNull(first.notification)
        val second = AlertPolicy.evaluate(first.state, service, check(false, 2_000))
        assertEquals("DOWN", second.state.confirmed)
        assertEquals("is down", second.notification)
        assertEquals(1_000L, second.state.candidateSince)
        assertEquals("recovered", AlertPolicy.evaluate(second.state, service, check(true, 3_000)).notification)
        val skipped = AlertPolicy.evaluate(first.state, service, check(false, 2_000, true))
        assertEquals(0, skipped.state.downStreak)
        assertEquals("UP", skipped.state.confirmed)
    }
    @Test fun firstDownAndRepeatAlertsAreConfigurable() {
        val quiet = service.copy(failureThreshold = 1, notifyInitialDown = false)
        val initial = AlertPolicy.evaluate(AlertState(1), quiet, check(false))
        assertEquals("DOWN", initial.state.confirmed)
        assertNull(initial.notification)
        assertEquals("is still down", AlertPolicy.evaluate(initial.state, quiet.copy(repeatMinutes = 1), check(false, 61_000)).notification)
        assertEquals("is down", AlertPolicy.evaluate(AlertState(1), quiet.copy(notifyInitialDown = true), check(false)).notification)
        val twoSuccesses = service.copy(recoveryThreshold = 2)
        val firstUp = AlertPolicy.evaluate(initial.state, twoSuccesses, check(true))
        assertEquals("DOWN", firstUp.state.confirmed)
        assertEquals("recovered", AlertPolicy.evaluate(firstUp.state, twoSuccesses, check(true, 3_000)).notification)
    }
    @Test fun staleAndUnknownStatesUseSharedClock() {
        assertEquals("UP", MonitorStatus.label(service, check(true), true, 2_000))
        assertEquals("STALE", MonitorStatus.label(service, check(true), true, 602_000))
        assertEquals("UNKNOWN", MonitorStatus.label(service, check(false, skipped = true), true, 2_000))
        assertEquals("MAINTENANCE", MonitorStatus.label(service.copy(maintenanceUntil = 5_000), check(true), true, 2_000))
    }
    @Test fun newMonitorsRequireInternetByDefault() {
        val monitor = ServiceEntry(name = "API", url = "https://example.com", intervalSeconds = 300)
        assertTrue(monitor.requireValidatedInternet)
        assertNotNull(ConnectivityPolicy.skipReason(monitor, true, false, 0))
    }
    @Test fun connectivityAllowsLocalNetworksButCanRequireInternet() {
        assertNull(ConnectivityPolicy.skipReason(service, true, false, 0))
        assertNotNull(ConnectivityPolicy.skipReason(service.copy(requireValidatedInternet = true), true, false, 0))
        assertNotNull(ConnectivityPolicy.skipReason(service, false, false, 0))
        assertEquals("Maintenance window", ConnectivityPolicy.skipReason(service.copy(maintenanceUntil = 100), true, true, 0))
    }
    @Test fun schedulerRestoresDeadlinesAndHandlesClockChanges() {
        assertEquals(200_500L, CheckSchedule.restoredDeadline(service, check(true, 100_000), 200_000, 500))
        assertEquals(500L, CheckSchedule.restoredDeadline(service, check(true, 100_000), 500_000, 500))
        assertEquals(300_500L, CheckSchedule.restoredDeadline(service, check(true, 100_000), 50_000, 500))
        assertEquals(1, CheckSchedule.alarmSeconds(listOf(100, 1_000), 0))
        assertNull(CheckSchedule.alarmSeconds(emptyList(), 0))
    }
}
