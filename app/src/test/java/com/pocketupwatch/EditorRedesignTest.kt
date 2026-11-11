package com.pocketupwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.*
import androidx.compose.ui.test.*
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
        compose.onNodeWithText("Failures before DOWN").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsDisplayed()
        saveScreenshot("add-service-light")
        compose.onNodeWithText("Alerts & maintenance").performScrollTo().performClick()
        compose.onNodeWithText("Failures before DOWN").assertExists()
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
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(existing, saved)
    }
    @Test fun internetRequirementCanBeDisabledBeforeSavingNewService() {
        var saved: ServiceEntry? = null
        compose.setContent { screenshotView = LocalView.current; MaterialTheme { Surface { ServiceEditor(null, onCancel = {}, probe = ok, onSave = { saved = it }) } } }
        compose.onNodeWithText("Name").performTextInput("Local API")
        compose.onNodeWithText("Host, path and query").performTextInput("192.168.1.2/health")
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
