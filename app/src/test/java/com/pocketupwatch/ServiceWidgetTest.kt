package com.pocketupwatch

import android.app.Application
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServiceWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, MonitorDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private val manager = AppWidgetManager.getInstance(context)
    private val widgets = shadowOf(manager)
    private val now = System.currentTimeMillis()
    @After fun close() { MonitorService.active.value = false; db.close() }

    private suspend fun service(name: String = "Vaultwarden") = dao.add(ServiceEntry(name = name, url = "https://example.com/health", intervalSeconds = 300))
    private suspend fun record(id: Long, time: Long, up: Boolean = true, skipped: Boolean = false) {
        dao.record(CheckEntry(serviceId = id, time = time, up = up, responseMs = 100, detail = "test", skipped = skipped))
    }
    private fun bind(id: Int) {
        widgets.addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, ServiceWidget::class.java)
            initialLayout = R.layout.service_widget
        })
    }
    private fun text(view: View, id: Int) = view.findViewById<TextView>(id).text.toString()

    @Test fun uptimeUsesAllChecksInTheWindowForOnlyTheSelectedService() = runBlocking {
        val id = service()
        val other = service("Other API")
        repeat(600) { index ->
            record(id, now - (600 - index) * 120_000L, up = index >= 60)
            record(other, now - (600 - index) * 120_000L, up = false)
        }
        repeat(20) { record(id, now - (it + 1) * 180_000L, up = false, skipped = true) }
        record(id, now - ServiceWidgetSnapshot.WINDOW_MS - 1, up = false)
        record(id, now + 1_000, up = false)
        val snapshot = ServiceWidgetSnapshot.load(dao, id, now, active = true)
        assertEquals(540L, snapshot.upCount)
        assertEquals(60L, snapshot.downCount)
        assertEquals(20L, snapshot.skippedCount)
        assertEquals(0.9, snapshot.uptime!!, 0.0001)
        assertTrue(snapshot.data.buckets.size <= 97)
    }

    @Test fun emptyAndSkippedHistoryDoNotReportPerfectUptime() = runBlocking {
        val id = service()
        val empty = ServiceWidgetSnapshot.load(dao, id, now, active = true)
        assertNull(empty.uptime)
        assertEquals("PENDING", empty.status)
        record(id, now - 1_000, up = false, skipped = true)
        val skipped = ServiceWidgetSnapshot.load(dao, id, now, active = true)
        assertNull(skipped.uptime)
        assertEquals("UNKNOWN", skipped.status)
        val view = ServiceWidgetRenderer.views(context, 101, skipped).apply(context, FrameLayout(context))
        assertEquals("—", text(view, R.id.service_widget_uptime))
        assertEquals("No valid checks in 24h", text(view, R.id.service_widget_period))
        val graph = ServiceWidgetRenderer.graph(context, skipped)
        try { assertEquals(ContextCompat.getColor(context, R.color.service_widget_track), graph.getPixel(360, 60)) }
        finally { graph.recycle() }
    }

    @Test fun eachWidgetKeepsItsOwnSelectionAndOpensTheCorrectService() = runBlocking {
        val first = service("Production API")
        val second = service("Home router")
        record(first, now - 1_000)
        record(second, now - 1_000, up = false)
        MonitorService.active.value = true
        bind(101); bind(102)
        ServiceWidget.configure(context, manager, 101, first, dao)
        ServiceWidget.configure(context, manager, 102, second, dao)
        assertEquals(first, ServiceWidgetSettings.serviceId(context, 101))
        assertEquals(second, ServiceWidgetSettings.serviceId(context, 102))
        assertEquals("UP", text(widgets.getViewFor(101), R.id.service_widget_status))
        assertEquals("DOWN", text(widgets.getViewFor(102), R.id.service_widget_status))
        widgets.getViewFor(101).findViewById<View>(R.id.service_widget_root).performClick()
        assertEquals(first, shadowOf(context).nextStartedActivity.getLongExtra("serviceId", 0))
        widgets.getViewFor(102).findViewById<View>(R.id.service_widget_root).performClick()
        assertEquals(second, shadowOf(context).nextStartedActivity.getLongExtra("serviceId", 0))
        val firstClick = ServiceWidgetRenderer.clickIntent(context, 101, first)
        val secondClick = ServiceWidgetRenderer.clickIntent(context, 102, second)
        assertNotEquals(firstClick, secondClick)
        assertTrue(shadowOf(firstClick).isImmutable)
        ServiceWidget.configure(context, manager, 101, second, dao)
        assertEquals("Home router", text(widgets.getViewFor(101), R.id.service_widget_title))
        assertEquals(second, ServiceWidgetSettings.serviceId(context, 102))
    }

    @Test fun renamedPausedAndDeletedServicesRefreshWithoutLeavingOldData() = runBlocking {
        val id = service()
        record(id, now - 1_000)
        bind(101)
        ServiceWidget.configure(context, manager, 101, id, dao)
        dao.update(dao.getService(id)!!.copy(name = "Renamed service", enabled = false))
        ServiceWidget.update(context, manager, intArrayOf(101), dao)
        assertEquals("Renamed service", text(widgets.getViewFor(101), R.id.service_widget_title))
        assertEquals("PAUSED", text(widgets.getViewFor(101), R.id.service_widget_status))
        dao.delete(dao.getService(id)!!)
        ServiceWidget.update(context, manager, intArrayOf(101), dao)
        val removed = widgets.getViewFor(101)
        assertEquals("Service removed", text(removed, R.id.service_widget_title))
        assertEquals("—", text(removed, R.id.service_widget_uptime))
        removed.findViewById<View>(R.id.service_widget_root).performClick()
        val target = shadowOf(context).nextStartedActivity
        assertEquals(ServiceWidgetConfigureActivity::class.java.name, target.component!!.className)
        assertEquals(101, target.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
    }

    @Test fun unconfiguredWidgetOffersSetupAndRejectsMissingServices() = runBlocking {
        bind(101)
        ServiceWidget.update(context, manager, intArrayOf(101), dao)
        assertEquals("Choose a service", text(widgets.getViewFor(101), R.id.service_widget_title))
        val id = service()
        ServiceWidget.configure(context, manager, 101, id, dao)
        assertTrue(runCatching { ServiceWidget.configure(context, manager, 101, id + 100, dao) }.isFailure)
        assertEquals(id, ServiceWidgetSettings.serviceId(context, 101))
    }

    @Test fun restoringAndDeletingWidgetInstancesPreservesOtherSelections() {
        ServiceWidgetSettings.save(context, 101, 7)
        ServiceWidgetSettings.save(context, 102, 8)
        ServiceWidget().onRestored(context, intArrayOf(101, 102), intArrayOf(201, 202))
        assertNull(ServiceWidgetSettings.serviceId(context, 101))
        assertEquals(7L, ServiceWidgetSettings.serviceId(context, 201))
        assertEquals(8L, ServiceWidgetSettings.serviceId(context, 202))
        ServiceWidget().onDeleted(context, intArrayOf(201))
        assertNull(ServiceWidgetSettings.serviceId(context, 201))
        assertEquals(8L, ServiceWidgetSettings.serviceId(context, 202))
    }

    @Test fun monitoringUpdatesRefreshServiceWidgetsEvenWithoutAnOverviewWidget() {
        bind(101); bind(102)
        ServiceWidgetSettings.save(context, 101, 7)
        ServiceWidgetSettings.save(context, 102, 8)
        MonitoringWidget.updateAll(context, 7)
        val targeted = shadowOf(context).broadcastIntents.last { it.component == ComponentName(context, ServiceWidget::class.java) }
        assertArrayEquals(intArrayOf(101), targeted.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS))
        MonitoringWidget.updateAll(context)
        val broadcast = shadowOf(context).broadcastIntents.last { it.component == ComponentName(context, ServiceWidget::class.java) }
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_UPDATE, broadcast.action)
        assertEquals(setOf(101, 102), broadcast.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)!!.toSet())
    }

    @Test fun graphDistinguishesSuccessFailureSkippedChecksAndMissingSamples() {
        val start = 86_400_000L
        val snapshot = ServiceWidgetSnapshot(1, ServiceWidgetData(null, null, listOf(
            ChartBucket(start, 1, 1, 0, 10),
            ChartBucket(start + ServiceWidgetSnapshot.BUCKET_MS, 1, 0, 1, 10)
        )), start + ServiceWidgetSnapshot.WINDOW_MS, true)
        val graph = ServiceWidgetRenderer.graph(context, snapshot, 960, 100)
        try {
            assertEquals(ContextCompat.getColor(context, R.color.service_widget_up), graph.getPixel(4, 10))
            assertEquals(ContextCompat.getColor(context, R.color.service_widget_down), graph.getPixel(4, 90))
            assertEquals(ContextCompat.getColor(context, R.color.service_widget_up), graph.getPixel(14, 10))
            assertEquals(ContextCompat.getColor(context, R.color.service_widget_track), graph.getPixel(14, 90))
            assertEquals(ContextCompat.getColor(context, R.color.service_widget_track), graph.getPixel(24, 10))
        } finally { graph.recycle() }
    }

    @Test fun widgetRendersAtNormalAndCompactSizes() = preview("light")
    @Test @Config(qualifiers = "w412dp-h915dp-night-xxhdpi") fun widgetAlsoRendersInDarkMode() = preview("dark")

    private fun preview(theme: String) = runBlocking {
        val id = service()
        repeat(288) { index ->
            if (index !in 90..110) record(id, now - (288 - index) * 300_000L,
                up = index !in 40..47 && index !in 160..170, skipped = index in 210..214)
        }
        val snapshot = ServiceWidgetSnapshot.load(dao, id, now, active = true)
        for ((width, height) in listOf(320 to 220, 220 to 200, 180 to 200)) {
            val view = ServiceWidgetRenderer.views(context, 101, snapshot).apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val w = (width * density).roundToInt()
            val h = (height * density).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val graph = view.findViewById<View>(R.id.service_widget_graph)
            assertTrue("The graph must remain visible at $width dp", graph.height >= 24 * density)
            assertTrue("The footer must fit at $width dp", view.findViewById<View>(R.id.service_widget_updated).bottom <= h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val path = File("build/outputs/editor-preview/service-widget-$theme-${width}dp.png").apply { parentFile!!.mkdirs() }
                path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun configurationWithoutAWidgetIdCancelsCleanly() {
        val controller = Robolectric.buildActivity(ServiceWidgetConfigureActivity::class.java).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
        } finally { controller.destroy() }
    }
}
