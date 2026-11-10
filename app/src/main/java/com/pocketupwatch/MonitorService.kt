package com.pocketupwatch

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONTokener
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MonitorService : Service() {
    companion object { val active = MutableStateFlow(false) }
    private val permits = Semaphore(4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val running = mutableMapOf<Long, Job>()
    private val configs = mutableMapOf<Long, ServiceEntry>()
    override fun onCreate() {
        super.onCreate()
        active.value = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitor", "Monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("alerts", "Service alerts", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Pocket Upwatch is monitoring").setContentText("Service checks are running").setOngoing(true).setContentIntent(openIntent()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
        scope.launch {
            MonitorDb.get(this@MonitorService).dao().services().collect { services ->
                val enabled = services.filter { it.enabled }
                val ids = enabled.map { it.id }.toSet()
                running.keys.filter { it !in ids }.toList().forEach { running.remove(it)?.cancel(); configs.remove(it) }
                enabled.forEach { service ->
                    if (configs[service.id] != service || running[service.id]?.isActive != true) {
                        running.remove(service.id)?.cancel()
                        configs[service.id] = service
                        running[service.id] = launch { monitor(service) }
                    }
                }
            }
        }
    }
    private fun openIntent(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun matchesBody(type: String, expected: String, actual: String): Boolean = try {
        when (type) {
            "Text" -> actual == expected
            "JSON" -> org.json.JSONObject.wrap(JSONTokener(actual).nextValue()).toString() == org.json.JSONObject.wrap(JSONTokener(expected).nextValue()).toString()
            "XML" -> {
                val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); isNamespaceAware = true }
                fun parse(value: String) = factory.newDocumentBuilder().parse(InputSource(StringReader(value))).apply { normalizeDocument() }
                parse(actual).isEqualNode(parse(expected))
            }
            else -> true
        }
    } catch (_: Exception) { false }
    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        })
    }
    private suspend fun monitor(service: ServiceEntry) {
        val dao = MonitorDb.get(this).dao()
        while (currentCoroutineContext().isActive) {
            if (!online()) { delay(10_000); continue }
            val start = System.nanoTime()
            var up = false
            val detail = try {
                permits.withPermit { execute(client.newCall(Request.Builder().url(service.url).get().build())).use { response ->
                    val statusOk = if (service.expectedCode == 0) response.isSuccessful else response.code == service.expectedCode
                    val body = if (service.bodyType == "None") "" else response.peekBody(1_048_577).bytes().let { if (it.size > 1_048_576) null else it.toString(Charsets.UTF_8) }
                    val bodyOk = service.bodyType == "None" || (body != null && matchesBody(service.bodyType, service.expectedBody, body))
                    up = statusOk && bodyOk
                    "HTTP ${response.code}" + if (body == null) " · body exceeds 1 MiB" else if (!bodyOk) " · body mismatch" else ""
                } }
            } catch (e: CancellationException) { throw e } catch (e: java.net.SocketTimeoutException) { "Timeout" } catch (e: javax.net.ssl.SSLException) { "TLS failure" } catch (e: IOException) { "DNS or connection failure" } catch (e: Exception) { "Check failed" }
            if (!online()) { delay(10_000); continue }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val previous = dao.latest(service.id)
            currentCoroutineContext().ensureActive()
            if (!dao.recordIfUnchanged(service, CheckEntry(serviceId = service.id, time = System.currentTimeMillis(), up = up, responseMs = elapsed, detail = detail))) return
            currentCoroutineContext().ensureActive()
            if (previous != null && previous.up != up) {
                val message = if (up) "${service.name} recovered" else "${service.name} is down"
                val alert = NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(message).setContentText(detail).setContentIntent(openIntent()).setAutoCancel(true).build()
                getSystemService(NotificationManager::class.java).notify((service.id % Int.MAX_VALUE).toInt() + 2, alert)
            }
            delay(service.intervalSeconds * 1000L)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { active.value = false; scope.cancel(); super.onDestroy() }
}
