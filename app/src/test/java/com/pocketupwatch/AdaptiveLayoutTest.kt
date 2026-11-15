package com.pocketupwatch

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, MonitorDb::class.java).allowMainThreadQueries().build()
    private val vm = MonitorViewModel(app, db.dao())
    private var width by mutableStateOf(1280.dp)
    private var viewportHeight by mutableStateOf<Dp?>(null)
    private var fold by mutableStateOf<DisplayFold?>(null)
    private var view: View? = null
    private val now = System.currentTimeMillis()
    private val monitors = listOf(
        ServiceEntry(1, "Production API", "https://api.example.com/health", 300, tags = "production, api"),
        ServiceEntry(2, "Payments API", "https://pay.example.com/status", 300, tags = "production, api"),
        ServiceEntry(3, "Home server", "http://192.168.1.20:8080", 600, requireValidatedInternet = false, tags = "home"),
        ServiceEntry(4, "Staging API", "https://staging.example.com", 900, enabled = false, tags = "staging"),
        ServiceEntry(5, "Domain resolution", "dns://example.com", 600, tags = "production"),
        ServiceEntry(6, "Website certificate", "tls://example.com:443", 3600, tags = "production")
    ).mapIndexed { index, service -> service.copy(id = (6 - index).toLong()) }
    @Before fun seed() = runBlocking {
        AppSettings.prefs(app).edit().clear().putBoolean("onboarding_complete", true).commit()
        BackgroundDiagnostics.prefs(app).edit().clear().putLong("last_result", now).commit()
        monitors.forEach { db.dao().add(it) }
        (1..144).forEach { index -> db.dao().recordIfUnchanged(monitors[0], CheckEntry(serviceId = monitors[0].id,
            time = now - (145 - index) * 600_000L, up = index !in 68..71, responseMs = 85L + index * 17 % 145,
            detail = if (index in 68..71) "HTTP 503" else "HTTP 200")) }
        monitors.forEach { service -> db.dao().recordIfUnchanged(service, CheckEntry(serviceId = service.id, time = now - 60_000,
            up = service.id != 5L, responseMs = if (service.id == 5L) 410 else 124, detail = if (service.id == 5L) "HTTP 503" else "Check passed")) }
        MonitorService.active.value = true
    }
    @After fun close() { MonitorService.active.value = false; db.close(); AppSettings.prefs(app).edit().clear().commit() }
    private fun show() {
        compose.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalFoldOverride provides fold) {
                    Box(Modifier.width(width).then(viewportHeight?.let { Modifier.height(it) } ?: Modifier.fillMaxHeight())) { App(0, 0, "Dark", {}, vm) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Overview", substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun tabletKeepsListAndDetailsVisibleAndSelectionWorks() {
        show()
        compose.onNodeWithTag("tablet-navigation").assertIsDisplayed()
        compose.onNodeWithTag("primary-pane").assertIsDisplayed()
        compose.onNodeWithTag("secondary-pane").assertIsDisplayed()
        choose(6)
        compose.onNodeWithTag("detail-title").assertTextEquals("Production API")
        screenshot("tablet-overview-dark")
        choose(5)
        compose.onNodeWithTag("detail-title").assertTextEquals("Payments API")
        screenshot("tablet-service-dark")
    }
    @Test fun tabletSettingsKeepCategoriesBesideTheSelectedPage() {
        show()
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithTag("settings-page-title").assertTextEquals("Monitoring")
        compose.onNodeWithText("Backup and restore", substring = false).performClick()
        compose.onNodeWithTag("settings-page-title").assertTextEquals("Backup and restore")
        compose.onNodeWithText("Create encrypted backup").assertIsDisplayed()
        compose.onNodeWithText("Appearance and dashboard").assertIsDisplayed()
        screenshot("tablet-settings-dark")
    }
    @Test fun unfoldingAndRefoldingPreserveUnsavedFieldsAndCredentials() {
        show()
        compose.onNodeWithText("Add monitor").performClick()
        compose.onNodeWithText("Name").performTextInput("Nightly API")
        compose.onNodeWithTag("monitor-target").performTextInput("https://nightly.example.com/health")
        screenshot("tablet-editor-dark")
        compose.onNodeWithText("Authentication & headers").performScrollTo().performClick()
        compose.onNodeWithText("Authentication", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Bearer", substring = false).performClick()
        compose.onNodeWithText("Bearer token").performScrollTo().performTextInput("temporary-test-token")
        compose.runOnIdle { width = 412.dp }
        compose.onNodeWithTag("tablet-navigation").assertDoesNotExist()
        compose.onNodeWithText("Name").performScrollTo().assertTextContains("Nightly API")
        compose.onNodeWithTag("monitor-target").assertTextContains("nightly.example.com/health")
        compose.runOnIdle { width = 1280.dp }
        compose.onNodeWithTag("tablet-navigation").assertExists()
        if (compose.onAllNodesWithText("Bearer token").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("Authentication & headers").performScrollTo().performClick()
        compose.onNodeWithText("Bearer token").performScrollTo().assertTextContains("•".repeat("temporary-test-token".length))
    }
    @Test fun filtersSurviveAChangeFromTabletToPhoneAndBack() {
        show()
        compose.onNodeWithTag("filter-DOWN").performClick()
        compose.runOnIdle { width = 412.dp }
        compose.onNodeWithTag("tablet-navigation").assertDoesNotExist()
        compose.onNodeWithTag("filter-DOWN").assertIsSelected()
        compose.onNodeWithTag("monitor-5").assertExists()
        compose.onNodeWithTag("monitor-6").assertDoesNotExist()
        compose.runOnIdle { width = 1280.dp }
        compose.onNodeWithTag("filter-DOWN").assertIsSelected()
    }
    @Test fun aKeyboardSizedViewportKeepsTheTabletEditorActive() {
        show()
        compose.onNodeWithText("Add monitor").performClick()
        compose.onNodeWithText("Name").performTextInput("Still editing")
        compose.runOnIdle { viewportHeight = 400.dp }
        compose.onNodeWithTag("tablet-navigation").assertExists()
        compose.onNodeWithText("Name").performScrollTo().assertTextContains("Still editing")
    }
    @Test fun anExplicitSelectionAndChartPeriodSurviveFolding() {
        show()
        choose(6)
        compose.onNodeWithText("7d", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("168h window", substring = true).assertExists()
        compose.runOnIdle { width = 412.dp }
        compose.onNodeWithTag("tablet-navigation").assertDoesNotExist()
        compose.onNodeWithText("168h window", substring = true).assertExists()
        compose.runOnIdle { width = 1280.dp }
        compose.onNodeWithTag("detail-title").assertTextEquals("Production API")
        compose.onNodeWithText("168h window", substring = true).assertExists()
    }
    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi") fun tabletPortraitUsesBothPanes() {
        width = 800.dp
        show()
        compose.onNodeWithTag("primary-pane").assertIsDisplayed()
        compose.onNodeWithTag("secondary-pane").assertIsDisplayed()
        screenshot("tablet-portrait-dark")
    }
    @Test @Config(qualifiers = "w720dp-h900dp-mdpi") fun unfoldedFoldableAvoidsTheVerticalHinge() {
        width = 720.dp
        fold = DisplayFold(IntRect(352, 0, 368, 900), vertical = true)
        show()
        val left = compose.onNodeWithTag("primary-pane").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("secondary-pane").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= 344f)
        assertTrue(right.left >= 376f)
        screenshot("foldable-unfolded-dark")
    }
    @Test fun tabletopPostureKeepsBothPanesClearOfTheHorizontalFold() {
        fold = DisplayFold(IntRect(0, 392, 1280, 408), vertical = false)
        show()
        assertTrue(compose.onNodeWithTag("primary-pane").fetchSemanticsNode().boundsInRoot.bottom <= 384f)
        assertTrue(compose.onNodeWithTag("secondary-pane").fetchSemanticsNode().boundsInRoot.top >= 416f)
        screenshot("foldable-tabletop-dark")
    }
    @Test @Config(qualifiers = "w412dp-h915dp-mdpi") fun phoneUsesItsExistingSingleScreenNavigation() {
        width = 412.dp
        show()
        compose.onNodeWithTag("tablet-navigation").assertDoesNotExist()
        compose.onNodeWithTag("primary-pane").assertDoesNotExist()
        choose(6)
        compose.onNodeWithTag("filter-ALL").assertDoesNotExist()
        compose.onNodeWithTag("app-back").performClick()
        compose.onNodeWithTag("filter-ALL").assertExists()
    }
    @Test fun phoneLandscapeStaysCompactAndUnfoldedInnerWindowsExpand() {
        assertFalse(AdaptiveWindow(915.dp, 412.dp).tablet)
        assertFalse(AdaptiveWindow(599.dp, 900.dp).tablet)
        assertTrue(AdaptiveWindow(673.dp, 841.dp).workspace)
        assertTrue(AdaptiveWindow(800.dp, 1280.dp).workspace)
    }
    private fun choose(id: Long) {
        compose.onNodeWithTag("monitor-list").performScrollToNode(hasTestTag("monitor-$id"))
        compose.onNodeWithTag("monitor-$id").performClick()
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val root = requireNotNull(view).rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/outputs/editor-preview/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
