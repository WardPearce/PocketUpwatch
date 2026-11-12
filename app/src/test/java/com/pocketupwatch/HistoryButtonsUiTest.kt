package com.pocketupwatch

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class HistoryButtonsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, MonitorDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private var view: View? = null
    private var id = 0L
    @After fun close() { MonitorService.active.value = false; db.close() }

    private fun seed(count: Int) = runBlocking {
        id = dao.add(ServiceEntry(name = "Production API", url = "https://example.com/health", intervalSeconds = 60))
        val now = System.currentTimeMillis()
        repeat(count) { index -> dao.record(CheckEntry(serviceId = id, time = now - (count - index) * 60_000, up = index % 7 != 0, responseMs = 90L + index % 10, detail = if (index % 7 == 0) "HTTP 503" else "HTTP 200")) }
    }
    private fun showHistory() {
        compose.setContent { view = LocalView.current; MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Production API", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(16.dp)); RecentChecks(id, dao) } } } }
    }
    private fun waitForPage(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("history-loading").fetchSemanticsNodes().isEmpty() }

    @Test fun paginationNavigatesBothWaysAndStopsAtTheLastPage() {
        seed(120)
        showHistory()
        waitForPage("Page 1 of 3")
        compose.onNodeWithTag("history-previous").assertIsNotEnabled()
        compose.onNodeWithTag("check-120").assertExists()
        compose.onNodeWithTag("history-next").performClick()
        waitForPage("Page 2 of 3")
        compose.onNodeWithTag("check-70").assertExists()
        compose.onNodeWithTag("check-120").assertDoesNotExist()
        assertAligned()
        screenshot("history-page-2-light")
        compose.onNodeWithTag("history-previous").performClick()
        waitForPage("Page 1 of 3")
        compose.onNodeWithTag("check-120").assertExists()
        compose.onNodeWithTag("history-next").performClick()
        waitForPage("Page 2 of 3")
        compose.onNodeWithTag("history-next").performClick()
        waitForPage("Page 3 of 3")
        compose.onNodeWithTag("check-1").assertExists()
        compose.onNodeWithTag("history-next").assertIsNotEnabled()
    }
    @Test fun exactlyFullFinalPageDoesNotAllowAnEmptyNextPage() {
        seed(50)
        showHistory()
        waitForPage("Page 1 of 1")
        compose.onNodeWithTag("history-next").assertIsNotEnabled()
        compose.onNodeWithTag("history-previous").assertIsNotEnabled()
    }
    @Test fun emptyHistoryHasNoEnabledNavigation() {
        seed(0)
        showHistory()
        waitForPage("Page 1 of 1")
        compose.onNodeWithText("No checks yet").assertExists()
        compose.onNodeWithTag("history-next").assertIsNotEnabled()
        compose.onNodeWithTag("history-previous").assertIsNotEnabled()
    }
    @Test fun newChecksDoNotShiftOlderPagesAndRetentionClampsThePage() {
        seed(120)
        showHistory()
        waitForPage("Page 1 of 3")
        compose.onNodeWithTag("history-next").performClick()
        waitForPage("Page 2 of 3")
        runBlocking { dao.record(CheckEntry(serviceId = id, time = System.currentTimeMillis(), up = true, responseMs = 100, detail = "new")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("check-70").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-next").performClick()
        waitForPage("Page 3 of 3")
        runBlocking { dao.pruneChecks(System.currentTimeMillis() + 1_000) }
        waitForPage("Page 1 of 1")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("check-121").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-next").assertIsNotEnabled()
    }
    @Test
    @Config(qualifiers = "w320dp-h740dp-xhdpi")
    fun navigationRemainsAlignedOnSmallScreens() {
        seed(51)
        showHistory()
        waitForPage("Page 1 of 2")
        assertAligned()
        screenshot("history-small-screen")
    }
    @Test fun detailPlacesFilledActionsBesideStatus() {
        seed(80)
        val service = runBlocking { dao.getService(id)!! }
        val vm = MonitorViewModel(ApplicationProvider.getApplicationContext<Application>(), dao)
        MonitorService.active.value = true
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                Scaffold(topBar = { TopAppBar(title = { Text(service.name) }, navigationIcon = { AppIconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
                    Detail(service, vm, Modifier.padding(padding), onEdit = {}, onDeleted = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("UP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check now").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        val actions = compose.onNodeWithTag("service-actions").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("service-target").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(actions.bottom < target.top)
        screenshot("service-details-light")
    }
    private fun assertAligned() {
        val previous = compose.onNodeWithTag("history-previous").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("history-next").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(abs(previous.center.y - next.center.y) < 1f)
        Assert.assertTrue(abs(previous.width - next.width) < 1f)
        Assert.assertTrue(next.left - previous.right >= 12f)
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val root = requireNotNull(view).rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/outputs/editor-preview/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
