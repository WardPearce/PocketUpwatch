package com.pocketupwatch

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val service = ServiceEntry(id = 7, name = "API", url = "https://user:password@example.com/health?token=secret", intervalSeconds = 300, expectedBody = "private", requestBody = "private request", headersCiphertext = "device-secret", tags = "production,api")
    @Test fun publicBackupsOmitSecretsAndImportPausedCopies() {
        val backup = BackupCodec.encode(listOf(service), charArrayOf()) { mapOf("Authorization" to "Bearer secret") }
        assertFalse(backup.contains("password"))
        assertFalse(backup.contains("device-secret"))
        assertFalse(backup.contains("private request"))
        assertFalse(backup.contains("Bearer secret"))
        assertFalse(backup.contains("token=secret"))
        val restored = BackupCodec.decode(backup, charArrayOf()) { assertTrue(it.isEmpty()); "" }.single()
        assertEquals(0L, restored.id)
        assertFalse(restored.enabled)
        assertEquals("production,api", restored.tags)
        assertTrue(restored.url.contains("REDACTED"))
    }
    @Test fun encryptedBackupsRoundTripAndRejectWrongPasswordAndTampering() {
        val password = "correct horse battery".toCharArray()
        val backup = BackupCodec.encode(listOf(service), password) { mapOf("Authorization" to "Bearer secret") }
        assertFalse(backup.contains("example.com"))
        val restored = BackupCodec.decode(backup, password) { assertEquals("Bearer secret", it["Authorization"]); "new-device-ciphertext" }.single()
        assertEquals(service.url, restored.url)
        assertEquals(service.requestBody, restored.requestBody)
        assertEquals("new-device-ciphertext", restored.headersCiphertext)
        assertThrows(Exception::class.java) { BackupCodec.decode(backup, "wrong password".toCharArray()) { "" } }
        val tampered = backup.replace(Regex("(\"data\"\\s*:\\s*\")[A-Za-z0-9+/]"), "$1!")
        assertThrows(Exception::class.java) { BackupCodec.decode(tampered, password) { "" } }
    }
    @Test fun invalidAndFutureBackupsAreRejectedWithoutImport() {
        assertThrows(Exception::class.java) { BackupCodec.decode("{\"version\":2,\"monitors\":[]}", charArrayOf()) { "" } }
        assertThrows(Exception::class.java) { BackupCodec.decode("{\"version\":1,\"monitors\":[{}]}", charArrayOf()) { "" } }
        assertThrows(Exception::class.java) { BackupCodec.encode(listOf(service), "short".toCharArray()) { emptyMap() } }
    }
    @Test fun csvQuotesAndNeutralizesFormulaCells() {
        val csv = BackupCodec.historyCsv(listOf(CheckEntry(serviceId = 1, time = 123, up = false, responseMs = 20, detail = "=SUM(1,2)\"", skipped = true)))
        assertTrue(csv.contains("UNKNOWN"))
        assertTrue(csv.contains("\"'=SUM(1,2)\"\"\""))
        assertTrue(csv.endsWith("\r\n"))
    }
    @Test fun validationPreservesAny2xxAndRejectsUnsafeHeaders() {
        assertNull(ServiceValidation.error(service.copy(expectedCode = 0)))
        assertNotNull(ServiceValidation.error(service, mapOf("X-Test" to "value\r\nInjected: yes")))
        assertNotNull(ServiceValidation.error(service.copy(method = "HEAD", bodyType = "JSON", expectedBody = "{}")))
        assertNotNull(ServiceValidation.error(service.copy(bodyType = "JSON", expectedBody = "{")))
        assertNotNull(ServiceValidation.error(service.copy(dnsExpected = "example.com")))
        assertNull(ServiceValidation.error(service.copy(dnsExpected = "127.0.0.1, ::1")))
    }
}
