package com.pocketupwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorControlsTest {
    @Test fun intervalsUseReadableUnitsWithoutLosingCustomValues() {
        assertEquals("5min", formatInterval(300))
        assertEquals("1hour", formatInterval(3600))
        assertEquals("2hours", formatInterval(7200))
        assertEquals("24hours", formatInterval(86400))
        assertEquals("1hour 30min", formatInterval(5400))
        assertEquals("12min 30sec", formatInterval(750))
    }
}
