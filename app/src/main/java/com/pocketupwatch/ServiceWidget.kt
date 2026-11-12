package com.pocketupwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal object ServiceWidgetSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("service_widgets", Context.MODE_PRIVATE)
    fun serviceId(context: Context, widgetId: Int): Long? = prefs(context).getLong("service_$widgetId", 0).takeIf { it > 0 }
    fun save(context: Context, widgetId: Int, serviceId: Long) {
        require(widgetId > AppWidgetManager.INVALID_APPWIDGET_ID && serviceId > 0)
        check(prefs(context).edit().putLong("service_$widgetId", serviceId).commit())
    }
    fun delete(context: Context, ids: IntArray) {
        prefs(context).edit().apply { ids.forEach { remove("service_$it") } }.apply()
    }
    fun restore(context: Context, oldIds: IntArray, newIds: IntArray) {
        val saved = oldIds.map { serviceId(context, it) }
        prefs(context).edit().apply {
            oldIds.forEach { remove("service_$it") }
            newIds.forEachIndexed { index, id -> saved.getOrNull(index)?.let { putLong("service_$id", it) } }
        }.apply()
    }
}

internal data class ServiceWidgetSnapshot(val selectedId: Long?, val data: ServiceWidgetData, val now: Long, val active: Boolean) {
    val since get() = now - WINDOW_MS
    val upCount get() = data.buckets.sumOf { it.upCount }
    val downCount get() = data.buckets.sumOf { it.downCount }
    val skippedCount get() = data.buckets.sumOf { it.skippedCount }
    val uptime: Double? get() = (upCount + downCount).takeIf { it > 0 }?.let { upCount.toDouble() / it }
    val status: String get() = data.service?.let { MonitorStatus.label(it, data.latest, active, now) } ?: "SETUP"
    companion object {
        const val WINDOW_MS = 86_400_000L
        const val BUCKET_MS = 900_000L
        suspend fun load(dao: MonitorDao, selectedId: Long?, now: Long = System.currentTimeMillis(), active: Boolean = MonitorService.active.value): ServiceWidgetSnapshot {
            val data = if (selectedId == null) ServiceWidgetData(null, null, emptyList())
                else dao.widgetData(selectedId, now - WINDOW_MS, now, BUCKET_MS)
            return ServiceWidgetSnapshot(selectedId, data, now, active)
        }
    }
}

internal object ServiceWidgetRenderer {
    fun views(context: Context, widgetId: Int, snapshot: ServiceWidgetSnapshot): RemoteViews {
        val service = snapshot.data.service
        val views = RemoteViews(context.packageName, R.layout.service_widget)
        val title = service?.name ?: if (snapshot.selectedId == null) "Choose a service" else "Service removed"
        val uptime = snapshot.uptime?.let { String.format(Locale.getDefault(), "%.1f%%", it * 100) } ?: "—"
        views.setTextViewText(R.id.service_widget_title, title)
        views.setTextViewText(R.id.service_widget_status, snapshot.status)
        views.setTextColor(R.id.service_widget_status, color(context, when (snapshot.status) {
            "UP" -> R.color.service_widget_up
            "DOWN" -> R.color.service_widget_down
            else -> R.color.service_widget_unknown
        }))
        views.setTextViewText(R.id.service_widget_uptime, uptime)
        views.setTextViewText(R.id.service_widget_period, when {
            service == null -> "Select a service to configure"
            snapshot.uptime == null -> "No valid checks in 24h"
            else -> context.getString(R.string.service_widget_period)
        })
        views.setImageViewBitmap(R.id.service_widget_graph, graph(context, snapshot))
        views.setContentDescription(R.id.service_widget_graph,
            "Last 24 hours: ${snapshot.upCount} up, ${snapshot.downCount} down, ${snapshot.skippedCount} skipped checks. Gray areas have no data or skipped checks.")
        views.setTextViewText(R.id.service_widget_updated, if (service == null) "Tap to choose a service" else
            "Snapshot ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.now))} · tap to open")
        views.setOnClickPendingIntent(R.id.service_widget_root, clickIntent(context, widgetId, service?.id))
        return views
    }

    fun clickIntent(context: Context, widgetId: Int, serviceId: Long?): PendingIntent {
        val intent = if (serviceId == null) Intent(context, ServiceWidgetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        else Intent(context, MainActivity::class.java).putExtra("serviceId", serviceId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Distinct intent identities keep each widget's destination independent of notification intents.
        intent.data = Uri.parse("pocketupwatch://service-widget/$widgetId/${serviceId ?: "configure"}")
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun graph(context: Context, snapshot: ServiceWidgetSnapshot, width: Int = 720, height: Int = 120): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color(context, R.color.service_widget_track))
        val paint = Paint()
        for (bucket in snapshot.data.buckets) {
            val total = bucket.upCount + bucket.downCount + bucket.skippedCount
            if (total == 0L) continue
            val left = ((bucket.time - snapshot.since).toDouble() / ServiceWidgetSnapshot.WINDOW_MS * width).toFloat().coerceIn(0f, width.toFloat())
            val right = ((bucket.time + ServiceWidgetSnapshot.BUCKET_MS - snapshot.since).toDouble() / ServiceWidgetSnapshot.WINDOW_MS * width).toFloat().coerceIn(0f, width.toFloat())
            if (right <= left) continue
            val upHeight = height * bucket.upCount.toFloat() / total
            val downHeight = height * bucket.downCount.toFloat() / total
            val end = (right - 1f).coerceAtLeast(left)
            paint.color = color(context, R.color.service_widget_up)
            canvas.drawRect(left, 0f, end, upHeight, paint)
            paint.color = color(context, R.color.service_widget_down)
            canvas.drawRect(left, upHeight, end, upHeight + downHeight, paint)
        }
        return bitmap
    }

    private fun color(context: Context, id: Int) = ContextCompat.getColor(context, id)
}

class ServiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context, manager, ids, goAsync()) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { refresh(context, manager, intArrayOf(id), goAsync()) }
    override fun onDeleted(context: Context, ids: IntArray) { ServiceWidgetSettings.delete(context, ids) }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        ServiceWidgetSettings.restore(context, oldIds, newIds)
        // AppWidgetProvider dispatches onUpdate with the restored IDs after this callback.
    }
    companion object {
        internal suspend fun configure(context: Context, manager: AppWidgetManager, widgetId: Int, serviceId: Long, dao: MonitorDao = MonitorDb.get(context).dao()) {
            val snapshot = ServiceWidgetSnapshot.load(dao, serviceId)
            check(snapshot.data.service != null) { "Service no longer exists" }
            val views = ServiceWidgetRenderer.views(context, widgetId, snapshot)
            val previous = ServiceWidgetSettings.serviceId(context, widgetId)
            ServiceWidgetSettings.save(context, widgetId, serviceId)
            try { manager.updateAppWidget(widgetId, views) }
            catch (e: Exception) {
                if (previous == null) ServiceWidgetSettings.delete(context, intArrayOf(widgetId))
                else ServiceWidgetSettings.save(context, widgetId, previous)
                throw e
            }
        }
        fun updateAll(context: Context, serviceId: Long? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ServiceWidget::class.java))
                .filter { serviceId == null || ServiceWidgetSettings.serviceId(context, it) == serviceId }.toIntArray()
            if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, ServiceWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
        internal suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray, dao: MonitorDao = MonitorDb.get(context).dao()) {
            val snapshots = mutableMapOf<Long?, ServiceWidgetSnapshot>()
            val now = System.currentTimeMillis()
            for (id in ids) {
                val selected = ServiceWidgetSettings.serviceId(context, id)
                val snapshot = snapshots[selected] ?: ServiceWidgetSnapshot.load(dao, selected, now).also { snapshots[selected] = it }
                manager.updateAppWidget(id, ServiceWidgetRenderer.views(context, id, snapshot))
            }
        }
        private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray, pending: BroadcastReceiver.PendingResult) {
            CoroutineScope(Dispatchers.IO).launch {
                try { withTimeout(8_000) { update(context, manager, ids) } }
                catch (_: Exception) { /* Preserve the previous timestamped snapshot if storage is unavailable. */ }
                finally { pending.finish() }
            }
        }
    }
}
