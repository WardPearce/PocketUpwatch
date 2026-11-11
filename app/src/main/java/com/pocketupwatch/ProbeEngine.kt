package com.pocketupwatch

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.*
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ProbeResult(val up: Boolean?, val detail: String, val responseMs: Long = 0)
internal fun interface Probe { suspend fun execute(service: ServiceEntry): ProbeResult }
internal class ProbeRunner(private val probe: Probe, parallelism: Int = 4, private val nanoTime: () -> Long = System::nanoTime) {
    private val slots = Semaphore(parallelism)
    suspend fun run(service: ServiceEntry): ProbeResult = slots.withPermit {
        val start = nanoTime()
        val result = probe.execute(service)
        result.copy(responseMs = (nanoTime() - start).coerceAtLeast(0) / 1_000_000)
    }
}

internal class ProbeEngine(
    private val headers: (String) -> Map<String, String>,
    private val context: Context? = null,
    private val tlsSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val dnsLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
) : Probe {
    private val client = OkHttpClient()
    override suspend fun execute(service: ServiceEntry): ProbeResult {
        val lock = context?.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketUpwatch:probe")
        lock?.acquire((service.timeoutSeconds + 10) * 1_000L)
        return try {
            val target = MonitorTarget.parse(service.url) ?: return ProbeResult(null, "Invalid target")
            withTimeout(service.timeoutSeconds * 1_000L) {
                when (target.protocol) {
                    "http", "https" -> http(service)
                    "tcp" -> runInterruptible(Dispatchers.IO) {
                        Socket().use { it.connect(InetSocketAddress(target.host, target.port), service.timeoutSeconds * 1_000) }
                        ProbeResult(true, "TCP connection successful")
                    }
                    "ping" -> ping(service, target)
                    "dns" -> runInterruptible(Dispatchers.IO) {
                        val addresses = dnsLookup(target.host).mapNotNull { it.hostAddress }.toSet()
                        val expected = service.dnsExpected.split(',').map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { InetAddress.getByName(it).hostAddress }.toSet()
                        val matches = expected.isEmpty() || addresses.containsAll(expected)
                        ProbeResult(matches, if (matches) "DNS: ${addresses.sorted().joinToString()}" else "DNS answer mismatch: ${addresses.sorted().joinToString()}")
                    }
                    "tls" -> tls(service, target)
                    else -> ProbeResult(null, "Unsupported protocol")
                }
            }
        } catch (_: TimeoutCancellationException) { ProbeResult(false, "Timeout") }
        catch (e: CancellationException) { throw e }
        catch (_: UnknownHostException) { ProbeResult(false, "DNS resolution failed") }
        catch (_: SocketTimeoutException) { ProbeResult(false, "Timeout") }
        catch (_: java.io.InterruptedIOException) { ProbeResult(false, "Timeout") }
        catch (_: SSLException) { ProbeResult(false, "TLS validation failed") }
        catch (_: ConnectException) { ProbeResult(false, "Connection refused or unreachable") }
        catch (_: IOException) { ProbeResult(false, "Connection failed") }
        catch (_: Exception) { ProbeResult(null, "Invalid configuration or unavailable credentials") }
        finally { if (lock?.isHeld == true) lock.release() }
    }
    private suspend fun http(service: ServiceEntry): ProbeResult {
        val configured = client.newBuilder().callTimeout(service.timeoutSeconds.toLong(), TimeUnit.SECONDS).followRedirects(service.followRedirects).followSslRedirects(service.followRedirects).build()
        val request = Request.Builder().url(service.url)
        headers(service.headersCiphertext).forEach { (name, value) -> request.header(name, value) }
        val body = if (service.method in listOf("POST", "PUT", "PATCH")) service.requestBody.toRequestBody((request.build().header("Content-Type") ?: "application/json").toMediaType()) else null
        request.method(service.method, body)
        return await(configured.newCall(request.build())).use { response ->
            val statusOk = if (service.expectedCode == 0) response.isSuccessful else response.code == service.expectedCode
            val actual = if (service.bodyType == "None") "" else response.peekBody(1_048_577).bytes().let { if (it.size > 1_048_576) null else it.toString(Charsets.UTF_8) }
            val bodyOk = service.bodyType == "None" || actual != null && BodyMatcher.matches(service.bodyType, service.expectedBody, actual, service.jsonPath)
            ProbeResult(statusOk && bodyOk, "HTTP ${response.code}" + when { actual == null -> " · body exceeds 1 MiB"; !bodyOk -> " · body mismatch"; else -> "" })
        }
    }
    private suspend fun ping(service: ServiceEntry, target: MonitorTarget): ProbeResult = runInterruptible(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", service.timeoutSeconds.toString(), target.host).redirectErrorStream(true).start()
        try {
            val finished = process.waitFor(service.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            ProbeResult(finished && process.exitValue() == 0, if (!finished) "Ping timed out" else if (process.exitValue() == 0) "Ping reply received" else "No ping reply")
        } finally { process.destroy() }
    }
    private suspend fun tls(service: ServiceEntry, target: MonitorTarget): ProbeResult = runInterruptible(Dispatchers.IO) {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(target.host, target.port), service.timeoutSeconds * 1_000)
            (tlsSocketFactory.createSocket(raw, target.host, target.port, true) as SSLSocket).use { socket ->
                socket.soTimeout = service.timeoutSeconds * 1_000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                val certificate = socket.session.peerCertificates.first() as X509Certificate
                val days = (certificate.notAfter.time - System.currentTimeMillis()) / 86_400_000
                ProbeResult(days >= service.certificateWarnDays, "TLS certificate expires in $days days")
            }
        } finally { raw.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        })
    }
    companion object {
        @Volatile private var shared: ProbeRunner? = null
        fun runner(context: Context): ProbeRunner = shared ?: synchronized(this) { shared ?: ProbeRunner(ProbeEngine(SecretStore()::decrypt, context.applicationContext)).also { shared = it } }
    }
}
