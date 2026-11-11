package com.pocketupwatch

import org.junit.Assert.*
import org.junit.Test

class MonitorTargetTest {
    @Test fun supportsExistingHttpUrlsAndNewTargets() {
        assertEquals(443, MonitorTarget.parse("tls://example.com")?.port)
        assertEquals(8443, MonitorTarget.parse("tls://example.com:8443")?.port)
        assertEquals("dns", MonitorTarget.parse("dns://example.com")?.protocol)
        assertEquals("https", MonitorTarget.parse("https://user:pass@example.com/path?q=1")?.protocol)
        assertEquals("example.com", MonitorTarget.parse("ping://example.com")?.host)
        assertEquals(443, MonitorTarget.parse("tcp://example.com:443")?.port)
        assertEquals("::1", MonitorTarget.parse("tcp://[::1]:8080")?.host)
    }

    @Test fun rejectsInvalidTargets() {
        listOf("tcp://example.com", "tcp://example.com:0", "tcp://example.com:65536", "tcp://example.com:80/path", "ping://example.com:80", "ping://example.com/path", "ping://-c", "ftp://example.com", "https://", "dns://example.com:53", "tls://example.com/path", "dns://user:pass@example.com").forEach {
            assertNull(it, MonitorTarget.parse(it))
        }
    }
}
