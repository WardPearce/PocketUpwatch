package com.pocketupwatch

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeEngineTest {
    private val service = ServiceEntry(id = 1, name = "API", url = "http://localhost", intervalSeconds = 300)
    @Test fun queueTimeIsExcludedAndConcurrencyIsBounded() = runTest {
        var active = 0
        var maximum = 0
        val runner = ProbeRunner(Probe { active++; maximum = maxOf(maximum, active); delay(100); active--; ProbeResult(true, "ok") }, 4) { testScheduler.currentTime * 1_000_000 }
        val results = (1..9).map { async { runner.run(service) } }.awaitAll()
        assertEquals(4, maximum)
        assertEquals(300L, testScheduler.currentTime)
        assertTrue(results.all { it.responseMs == 100L })
    }
    @Test fun headersMethodsBodiesAndJsonPathsReachTheServer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"status\":\"ok\",\"time\":123}"))
            val engine = ProbeEngine({ mapOf("Authorization" to "Bearer secret", "X-Health" to "yes") })
            val result = engine.execute(service.copy(url = server.url("/health").toString(), method = "POST", requestBody = "{\"probe\":true}", bodyType = "JSON", expectedBody = "\"ok\"", jsonPath = "$.status"))
            assertEquals(true, result.up)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("yes", request.getHeader("X-Health"))
            assertEquals("{\"probe\":true}", request.body.readUtf8())
        }
    }
    @Test fun exactStatusRedirectPolicyAndBodyLimitAreEnforced() = runBlocking {
        MockWebServer().use { server ->
            val engine = ProbeEngine({ emptyMap() })
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(false, engine.execute(service.copy(url = server.url("/").toString(), expectedCode = 200)).up)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/new"))
            assertEquals(true, engine.execute(service.copy(url = server.url("/").toString(), expectedCode = 302, followRedirects = false)).up)
            server.enqueue(MockResponse().setBody("a".repeat(1_048_577)))
            val oversized = engine.execute(service.copy(url = server.url("/").toString(), bodyType = "Text", expectedBody = "a"))
            assertEquals(false, oversized.up)
            assertTrue(oversized.detail.contains("1 MiB"))
        }
    }
    @Test fun cancelledHttpCallsDoNotProduceResults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok").setHeadersDelay(5, TimeUnit.SECONDS))
            var recorded = false
            val job = launch(Dispatchers.IO) { ProbeEngine({ emptyMap() }).execute(service.copy(url = server.url("/").toString())); recorded = true }
            withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            job.cancelAndJoin()
            assertFalse(recorded)
        }
    }
    @Test fun headHasNoRequestBodyAndTimeoutIsReported() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val engine = ProbeEngine({ emptyMap() })
            assertEquals(true, engine.execute(service.copy(url = server.url("/").toString(), method = "HEAD")).up)
            assertEquals("HEAD", server.takeRequest().method)
            server.enqueue(MockResponse().setBody("ok").setHeadersDelay(3, TimeUnit.SECONDS))
            val result = engine.execute(service.copy(url = server.url("/").toString(), timeoutSeconds = 1))
            assertEquals(false, result.up)
            assertEquals("Timeout", result.detail)
        }
    }
    @Test fun dnsChecksNormalizeAnswersAndRequireEveryExpectedAddress() = runBlocking {
        val engine = ProbeEngine({ emptyMap() }, dnsLookup = { listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")) })
        assertEquals(true, engine.execute(service.copy(url = "dns://example.com", dnsExpected = "::1, 127.0.0.1")).up)
        assertEquals(false, engine.execute(service.copy(url = "dns://example.com", dnsExpected = "127.0.0.2")).up)
    }
    @Test fun tlsChecksValidateHostnameAndExpiryThreshold() = runBlocking {
        val now = System.currentTimeMillis()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").validityInterval(now - 86_400_000, now + 2 * 86_400_000).build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val engine = ProbeEngine({ emptyMap() }, tlsSocketFactory = trusted.sslSocketFactory())
            val monitor = service.copy(url = "tls://localhost:${server.port}", certificateWarnDays = 30)
            val expiring = engine.execute(monitor)
            assertEquals(false, expiring.up)
            assertTrue(expiring.detail.contains("expires in"))
            assertEquals(true, engine.execute(monitor.copy(certificateWarnDays = 0)).up)
            val mismatch = engine.execute(monitor.copy(url = "tls://127.0.0.1:${server.port}", certificateWarnDays = 0))
            assertEquals(false, mismatch.up)
            assertEquals("TLS validation failed", mismatch.detail)
        }
    }

}
