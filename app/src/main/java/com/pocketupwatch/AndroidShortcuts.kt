package com.pocketupwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MonitoringWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context, ids, goAsync()) }
    companion object {
        fun updateAll(context: Context, serviceId: Long? = null) {
            ServiceWidget.updateAll(context, serviceId)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MonitoringWidget::class.java))
            if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, MonitoringWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
        private fun refresh(context: Context, ids: IntArray, pending: android.content.BroadcastReceiver.PendingResult) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = MonitorDb.get(context).dao()
                    val services = dao.allServices()
                    val checks = dao.latestChecks().first().associateBy { it.serviceId }
                    val states = services.map { MonitorStatus.label(it, checks[it.id], MonitorService.active.value, System.currentTimeMillis()) }
                    val manager = AppWidgetManager.getInstance(context)
                    ids.forEach { id ->
                        val views = RemoteViews(context.packageName, R.layout.monitoring_widget)
                        views.setTextViewText(R.id.widget_title, "Pocket Upwatch")
                        views.setTextViewText(R.id.widget_status, "${states.count { it == "UP" }} up · ${states.count { it == "DOWN" }} down\n${states.count { it !in listOf("UP", "DOWN", "PAUSED") }} unknown/stopped · ${states.count { it == "PAUSED" }} paused")
                        views.setTextViewText(R.id.widget_updated, "Snapshot ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())} · tap to open")
                        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        manager.updateAppWidget(id, views)
                    }
                } catch (_: Exception) { /* Keep the previous timestamped snapshot if storage is unavailable. */ }
                finally { pending.finish() }
            }
        }
    }
}

class MonitoringTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        observing?.cancel()
        observing = scope.launch {
            MonitorDb.get(this@MonitoringTile).dao().services().collect { services ->
                qsTile?.apply {
                    state = if (services.isEmpty()) Tile.STATE_UNAVAILABLE else if (services.any { it.enabled }) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = "Pocket Upwatch"
                    if (android.os.Build.VERSION.SDK_INT >= 29) subtitle = if (services.any { it.enabled }) "Tap to pause all" else "Tap to resume all"
                    updateTile()
                }
            }
        }
    }
    override fun onStopListening() { observing?.cancel(); super.onStopListening() }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            scope.launch {
                val dao = MonitorDb.get(this@MonitoringTile).dao()
                val services = dao.allServices()
                if (services.isNotEmpty()) {
                    val enable = services.none { it.enabled }
                    dao.setAllEnabled(enable)
                    if (enable) runCatching { MonitorService.start(this@MonitoringTile) }
                    MonitoringWidget.updateAll(this@MonitoringTile)
                }
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
