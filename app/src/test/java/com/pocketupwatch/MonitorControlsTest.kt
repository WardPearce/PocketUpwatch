package com.pocketupwatch

import org.junit.Assert.*
import org.junit.Test

class MonitorControlsTest {
    @Test fun intervalsUseReadableUnitsWithoutLosingCustomValues() {
        assertEquals("1 min", formatInterval(60))
        assertEquals("5 min", formatInterval(300))
        assertEquals("1 hour", formatInterval(3600))
        assertEquals("2 hours", formatInterval(7200))
        assertEquals("24 hours", formatInterval(86400))
        assertEquals("1 hour 30 min", formatInterval(5400))
        assertEquals("12 min 30 sec", formatInterval(750))
    }
    @Test fun fiveMinutesIsTheMinimumSupportedInterval() {
        val service = ServiceEntry(name = "API", url = "https://example.com", intervalSeconds = 300)
        assertNull(ServiceValidation.error(service))
        assertNotNull(ServiceValidation.error(service.copy(intervalSeconds = 299)))
        assertNull(ServiceValidation.error(service.copy(intervalSeconds = 86400)))
        assertNotNull(ServiceValidation.error(service.copy(intervalSeconds = 86401)))
    }

}
