package com.pocketupwatch

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

internal object ConnectivityPolicy {
    fun skipReason(service: ServiceEntry, connected: Boolean, validated: Boolean, now: Long): String? = when {
        service.maintenanceUntil > now -> "Maintenance window"
        else -> internetSkipReason(service, connected, validated)
    }
    fun internetSkipReason(service: ServiceEntry, connected: Boolean, validated: Boolean): String? = when {
        !connected -> "No network connection"
        service.requireValidatedInternet && !validated -> "Internet connection is not validated"
        else -> null
    }
}

class MonitorService : Service() {
    companion object {
        val active = MutableStateFlow(false)
        val checksInProgress = MutableStateFlow<Set<Long>>(emptySet())
        const val ACTION_RUN = "com.pocketupwatch.RUN_NOW"
        fun start(context: Context, id: Long? = null) {
            val intent = Intent(context, MonitorService::class.java)
            if (id != null) {
                intent.setAction(ACTION_RUN).putExtra("serviceId", id)
                checksInProgress.value += id
            }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (e: Exception) {
                if (id != null) checksInProgress.value -= id
                throw e
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val checkSlots = Semaphore(4)
    private val running = mutableMapOf<Long, Job>()
    private val configs = mutableMapOf<Long, ServiceEntry>()
    private val deadlines = mutableMapOf<Long, Long>()
    private val forced = mutableSetOf<Long>()
    private var networkSignature: Pair<Boolean, Boolean>? = null
    private var lastPruned = 0L
    private val network by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wake.trySend(Unit) }
        override fun onLost(network: Network) { wake.trySend(Unit) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { wake.trySend(Unit) }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitor", "Monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("alerts", "Service alerts", NotificationManager.IMPORTANCE_HIGH))
        val notification = notification("Starting monitoring")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
        active.value = true
        MonitoringWidget.updateAll(this)
        network.registerDefaultNetworkCallback(callback)
        scope.launch { MonitorDb.get(this@MonitorService).dao().services().collect { wake.trySend(Unit) } }
        scope.launch { scheduler() }
    }
    private fun publish(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Pocket Upwatch").setContentText(text).setOngoing(true).setContentIntent(openIntent()).build()
    private fun openIntent(id: Long = 0): PendingIntent = PendingIntent.getActivity(this, (id % Int.MAX_VALUE).toInt(), Intent(this, MainActivity::class.java).putExtra("serviceId", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun connectivity(): Pair<Boolean, Boolean> {
        val current = network.activeNetwork
        return (current != null) to (current?.let { network.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true)
    }
    private suspend fun scheduler() {
        wake.trySend(Unit)
        while (currentCoroutineContext().isActive) {
            wake.receive()
            try {
                withBackgroundWakeLock(this, "scheduler", 90_000) {
                    val dao = MonitorDb.get(this).dao()
                    val enabled = dao.allServices().filter { it.enabled }
                    val ids = enabled.map { it.id }.toSet()
                    (configs.keys - ids).forEach { id -> running.remove(id)?.cancelAndJoin(); configs.remove(id); deadlines.remove(id) }
                    checksInProgress.value = checksInProgress.value.intersect(ids)
                    if (enabled.isEmpty()) {
                        CheckAlarm.schedule(this, null)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        active.value = false
                        MonitoringWidget.updateAll(this)
                        return@withBackgroundWakeLock
                    }
                    val now = SystemClock.elapsedRealtime()
                    val wall = System.currentTimeMillis()
                    val connection = connectivity()
                    val networkChanged = networkSignature != null && connection != networkSignature && connection.first
                    networkSignature = connection
                    enabled.forEach { service ->
                        val prior = configs[service.id]
                        if (prior != service) {
                            running.remove(service.id)?.cancelAndJoin()
                            configs[service.id] = service
                            deadlines[service.id] = if (prior == null) CheckSchedule.restoredDeadline(service, dao.latest(service.id), wall, now) else now
                        }
                        if (service.id in forced || networkChanged && running[service.id]?.isActive != true) deadlines[service.id] = now
                    }
                    forced.clear()
                    enabled.forEach { service ->
                        if (running[service.id]?.isActive != true && (deadlines[service.id] ?: now) <= now) {
                            deadlines[service.id] = Long.MAX_VALUE
                            checksInProgress.value += service.id
                            running[service.id] = scope.launch {
                                checkSlots.withPermit {
                                    withBackgroundWakeLock(this@MonitorService, "check", (service.timeoutSeconds + 90) * 1_000L) {
                                        try { check(service) }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) {
                                            BackgroundDiagnostics.failure(this@MonitorService, "Check persistence", e)
                                            publish(1, notification("Could not store a check · retrying next interval"))
                                        } finally {
                                            checksInProgress.value -= service.id
                                            if (configs[service.id] == service) deadlines[service.id] = SystemClock.elapsedRealtime() + service.intervalSeconds.coerceAtLeast(300) * 1_000L
                                            try {
                                                if (active.value) CheckAlarm.schedule(this@MonitorService, CheckSchedule.alarmSeconds(deadlines.values.filter { it != Long.MAX_VALUE }, SystemClock.elapsedRealtime()) ?: 300)
                                            } catch (e: Exception) { BackgroundDiagnostics.failure(this@MonitorService, "Alarm scheduling", e) }
                                            finally { wake.trySend(Unit) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val pending = deadlines.values.filter { it != Long.MAX_VALUE }
                    val seconds = CheckSchedule.alarmSeconds(pending, SystemClock.elapsedRealtime()) ?: enabled.minOf { it.intervalSeconds }
                    CheckAlarm.schedule(this, seconds)
                    publish(1, notification("${enabled.size} enabled · next check in about ${seconds.coerceAtLeast(1)} seconds"))
                    if (wall - lastPruned > 3_600_000) {
                        lastPruned = wall
                        val retention = getSharedPreferences("settings", MODE_PRIVATE).getInt("retention_days", 90).coerceIn(7, 365)
                        dao.pruneChecks(wall - retention * 86_400_000L)
                        dao.pruneHours(wall - 365 * 86_400_000L)
                    }
                    BackgroundDiagnostics.mark(this, "last_cycle")
                }
                if (!active.value) return
                val seconds = CheckSchedule.alarmSeconds(deadlines.values.filter { it != Long.MAX_VALUE }, SystemClock.elapsedRealtime()) ?: 300
                withTimeoutOrNull(seconds * 1_000L) { wake.receive() }
                wake.trySend(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                BackgroundDiagnostics.failure(this, "Scheduler", e)
                publish(1, notification("Monitoring interrupted · retrying in one minute"))
                CheckAlarm.schedule(this, 60)
                delay(60_000)
                wake.trySend(Unit)
            }
        }
    }
    private suspend fun check(service: ServiceEntry) {
        val dao = MonitorDb.get(this).dao()
        val before = connectivity()
        val skip = ConnectivityPolicy.skipReason(service, before.first, before.second, System.currentTimeMillis())
        val result = if (skip != null) ProbeResult(null, skip) else withContext(Dispatchers.IO) { ProbeEngine.runner(this@MonitorService).run(service) }
        currentCoroutineContext().ensureActive()
        val event = dao.recordIfUnchanged(service, CheckEntry(serviceId = service.id, time = System.currentTimeMillis(), up = result.up == true, responseMs = result.responseMs, detail = result.detail, skipped = result.up == null))
        BackgroundDiagnostics.mark(this, "last_result")
        if (event != null && androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val alert = NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("${service.name} $event").setContentText(result.detail).setContentIntent(openIntent(service.id)).setAutoCancel(true).build()
            publish((service.id % (Int.MAX_VALUE - 2)).toInt() + 2, alert)
        }
        MonitoringWidget.updateAll(this, service.id)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUN) {
            val id = intent.getLongExtra("serviceId", 0)
            forced += id
            checksInProgress.value += id
        }
        wake.trySend(Unit)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        active.value = false
        runCatching { network.unregisterNetworkCallback(callback) }
        scope.cancel()
        checksInProgress.value = emptySet()
        MonitoringWidget.updateAll(this)
        super.onDestroy()
    }
}
