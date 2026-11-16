package com.pocketupwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRedesignTest {
    @get:Rule val compose = createComposeRule()
    private var screenshotView: View? = null
    private val ok = Probe { ProbeResult(true, "HTTP 200") }

    @Test fun newEditorKeepsEssentialsVisibleAndAdvancedSettingsCollapsed() {
        compose.setContent { screenshotView = LocalView.current; MaterialTheme { Surface { ServiceEditor(null, onCancel = {}, probe = ok, onSave = {}) } } }
        compose.onNodeWithText("Service details").assertIsDisplayed()
        compose.onNodeWithText("Monitoring").assertIsDisplayed()
        compose.onNodeWithContentDescription("Require Internet").assertIsOn()
        compose.onNodeWithText("Require validated internet").assertDoesNotExist()
        compose.onNodeWithText("Timeout (seconds)").assertDoesNotExist()
        compose.onNodeWithText("Bearer token").assertDoesNotExist()
        compose.onNodeWithText("Mark down after 1 failed check").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsDisplayed()
        saveScreenshot("add-service-light")
        compose.onNodeWithText("Alerts").performScrollTo().performClick()
        compose.onNodeWithText("Mark down after 1 failed check").assertExists()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
    @Test fun editorAlsoRendersInDarkTheme() {
        compose.setContent { screenshotView = LocalView.current; MaterialTheme(colorScheme = darkColorScheme()) { Surface { ServiceEditor(null, onCancel = {}, probe = ok, onSave = {}) } } }
        compose.onNodeWithContentDescription("Require Internet").assertIsOn()
        compose.onNodeWithText("Save").assertIsDisplayed()
        saveScreenshot("add-service-dark")
    }
    @Test fun existingLocalNetworkAndCollapsedOptionsArePreserved() {
        val existing = ServiceEntry(id = 1, name = "Local API", url = "http://192.168.1.2/health", intervalSeconds = 7200, requireValidatedInternet = false, method = "POST", requestBody = "{}", bodyType = "JSON", expectedBody = "true", jsonPath = "$.ok", expectedCode = 201, failureThreshold = 3, repeatMinutes = 15, tags = "home,api")
        var saved: ServiceEntry? = null
        compose.setContent { screenshotView = LocalView.current; MaterialTheme { Surface { ServiceEditor(existing, onCancel = {}, probe = ok, onSave = { saved = it }) } } }
        compose.onNodeWithContentDescription("Require Internet").assertIsOff()
        compose.onNodeWithText("Expected JSON value").assertDoesNotExist()
        compose.waitUntil(10_000) { compose.onNodeWithText("Save").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(existing, saved)
    }
    @Test fun internetRequirementCanBeDisabledBeforeSavingNewService() {
        var saved: ServiceEntry? = null
        compose.setContent { screenshotView = LocalView.current; MaterialTheme { Surface { ServiceEditor(null, onCancel = {}, probe = ok, onSave = { saved = it }) } } }
        compose.onNodeWithText("Name").performTextInput("Local API")
        compose.onNodeWithText("Address").performTextInput("192.168.1.2/health")
        compose.onNodeWithContentDescription("Require Internet").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertFalse(saved!!.requireValidatedInternet)
    }
    @Test fun requestSettingsCanBeExpandedEditedAndSaved() {
        val existing = ServiceEntry(id = 2, name = "API", url = "https://example.com/health", intervalSeconds = 300)
        var saved: ServiceEntry? = null
        compose.setContent { screenshotView = LocalView.current; MaterialTheme { Surface { ServiceEditor(existing, onCancel = {}, probe = ok, onSave = { saved = it }) } } }
        compose.onNodeWithText("Request settings").performScrollTo().performClick()
        compose.onNodeWithText("Timeout (seconds)").performScrollTo().performTextReplacement("8")
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(8, saved!!.timeoutSeconds)
    }
    @Test fun legacyOneMinuteIntervalIsRaisedToFiveMinutes() {
        val existing = ServiceEntry(id = 3, name = "Production API", url = "https://example.com/health", intervalSeconds = 60)
        var saved: ServiceEntry? = null
        compose.setContent { screenshotView = LocalView.current; MaterialTheme { Surface { ServiceEditor(existing, onCancel = {}, probe = ok, onSave = { saved = it }) } } }
        compose.onNodeWithTag("interval-battery-warning").assertDoesNotExist()
        compose.onNodeWithTag("interval-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Check interval: 5 min").assertExists()
        compose.onNodeWithTag("interval-battery-warning").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsEnabled()
        saveScreenshot("five-minute-minimum")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(300, saved!!.intervalSeconds)
    }
    @Test fun failureAndRecoverySlidersPreserveCountsAndSaveTheirBounds() {
        var saved: ServiceEntry? = null
        val existing = ServiceEntry(id = 1, name = "API", url = "https://example.com/", intervalSeconds = 300, failureThreshold = 3, recoveryThreshold = 2)
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = ok, onSave = { saved = it }) } }
        compose.onNodeWithText("Alerts").performScrollTo().performClick()
        compose.onNodeWithText("Mark down after 3 failed checks").assertExists()
        compose.onNodeWithText("Recover after 2 successful checks").assertExists()
        compose.onNodeWithTag("failure-threshold-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        compose.onNodeWithTag("recovery-threshold-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Mark down after 20 failed checks").assertExists()
        compose.onNodeWithText("Recover after 1 successful check").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(20, saved!!.failureThreshold)
        assertEquals(1, saved!!.recoveryThreshold)
    }
    @Test fun repeatAlertsDefaultToAnHourAndSliderSavesTheFiveMinuteMinimum() {
        var saved: ServiceEntry? = null
        compose.setContent { screenshotView = LocalView.current; MaterialTheme(colorScheme = darkColorScheme()) { Surface { ServiceEditor(null, onCancel = {}, probe = ok, onSave = { saved = it }) } } }
        compose.onNodeWithText("Name").performTextInput("Production API")
        compose.onNodeWithText("Address").performTextInput("api.example.com/health")
        compose.onNodeWithText("Alerts").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Repeat alerts").performScrollTo().assertIsOn()
        compose.onNodeWithText("Remind every 1 hour").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("repeat-alert-slider").performScrollTo()
        compose.onNodeWithText("Android may delay reminders.").performScrollTo()
        saveScreenshot("alert-reminders-dark")
        compose.onNodeWithTag("repeat-alert-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Remind every 5 min").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(5, saved!!.repeatMinutes)
    }
    @Test fun disabledRemindersStayOffAndCanBeEnabledThenDisabledAgain() {
        var saved: ServiceEntry? = null
        val existing = ServiceEntry(id = 1, name = "API", url = "https://example.com/", intervalSeconds = 300, repeatMinutes = 0)
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = ok, onSave = { saved = it }) } }
        compose.onNodeWithText("Alerts").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Repeat alerts").performScrollTo().assertIsOff()
        compose.onNodeWithTag("repeat-alert-slider").assertDoesNotExist()
        compose.onNodeWithContentDescription("Repeat alerts").performClick()
        compose.onNodeWithText("Remind every 1 hour").assertExists()
        compose.onNodeWithTag("repeat-alert-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        compose.onNodeWithText("Remind every 7 days").assertExists()
        compose.onNodeWithContentDescription("Repeat alerts").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(0, saved!!.repeatMinutes)
    }
    @Test fun customReminderIntervalsSurviveEditingAndToggling() {
        var saved: ServiceEntry? = null
        val existing = ServiceEntry(id = 1, name = "API", url = "https://example.com/", intervalSeconds = 300, repeatMinutes = 25)
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = ok, onSave = { saved = it }) } }
        compose.onNodeWithText("Alerts").performScrollTo().performClick()
        compose.onNodeWithText("Remind every 25 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Repeat alerts").performScrollTo().performClick().performClick()
        compose.onNodeWithText("Remind every 25 min").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(25, saved!!.repeatMinutes)
    }
    private fun saveScreenshot(name: String) {
        compose.runOnIdle {
            val view = requireNotNull(screenshotView).rootView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val path = File("build/outputs/editor-preview/$name.png").apply { parentFile!!.mkdirs() }
            path.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(path.length() > 5_000)
            image.recycle()
        }
    }
}
