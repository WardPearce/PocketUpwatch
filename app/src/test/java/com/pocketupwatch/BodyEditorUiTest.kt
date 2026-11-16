package com.pocketupwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BodyEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private var view: View? = null
    private fun editors(root: View = requireNotNull(view).rootView): List<CodeEditor> = when (root) {
        is CodeEditor -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { editors(root.getChildAt(it)) }
        else -> emptyList()
    }
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        view = LocalView.current
        MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { content() } }
    }
    private fun waitForHighlight() = compose.waitUntil(10_000) {
        var ready = false
        compose.runOnIdle { ready = editors().any { editor -> (0 until editor.text.lineCount).any { editor.getSpansForLine(it).size > 1 } } }
        ready
    }
    @Test fun soraHighlightsAndFormattingRequiresApplyAndCanBeUndone() {
        var text by mutableStateOf("{\"ok\":true,\"items\":[1,2]}")
        val original = text
        show { Column(Modifier.padding(16.dp)) { BodyEditor("Expected response", text, "JSON", onChange = { text = it }) } }
        waitForHighlight()
        compose.onNodeWithText("Format").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Format preview").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(original, text)
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(10_000) { text.contains('\n') }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(10_000) { text == original }
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.waitUntil(10_000) { text.contains('\n') }
        screenshot("response-json-dark")
    }
    @Test fun yamlEditorUsesSoraAndExpandedTextAndUndoSurvive() {
        var text by mutableStateOf("status: ready\nchecks:\n  database: true\n  cache: true\nversion: 3")
        show { Column(Modifier.padding(16.dp)) { BodyEditor("Expected response", text, "YAML", onChange = { text = it }) } }
        waitForHighlight()
        compose.runOnIdle { editors().single().text.insert(0, 0, "# Health response\n") }
        compose.waitUntil(10_000) { text.startsWith('#') }
        screenshot("response-yaml-dark")
        compose.onNodeWithText("Expand").performClick()
        compose.onNodeWithText("Done").assertExists().performClick()
        compose.waitUntil(10_000) { editors().isNotEmpty() }
        compose.runOnIdle { assertEquals(text, editors().single().text.toString()); assertTrue(editors().single().canUndo()) }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(10_000) { !text.startsWith('#') }
    }
    @Test fun expectedXmlPreviewIsReadOnlyAndHighlighted() {
        show { Column(Modifier.padding(16.dp)) { BodyEditor("Expected response", "<health status=\"ready\">\n  <database>true</database>\n</health>", "XML", readOnly = true) } }
        waitForHighlight()
        compose.runOnIdle { assertFalse(editors().single().isEditable) }
        compose.onNodeWithText("Format").assertDoesNotExist()
        compose.onNodeWithText("Copy").assertExists()
        screenshot("response-xml-dark")
    }
    @Test fun headerRowsCanBeAddedEditedAndDeleted() {
        var rows by mutableStateOf(emptyList<HeaderDraft>())
        show { Column(Modifier.padding(16.dp)) { HeaderEditor(rows, true) { rows = it } } }
        compose.onNodeWithText("Add header").performClick()
        compose.onNodeWithText("Header name").performTextInput("X-Environment")
        compose.onNodeWithText("Value").performTextInput("production")
        compose.runOnIdle { assertEquals("production", headerValues(rows)["X-Environment"]) }
        compose.onNodeWithText("Value").performTextReplacement("staging")
        screenshot("header-rows-dark")
        compose.onNodeWithContentDescription("Delete header X-Environment").performClick()
        compose.runOnIdle { assertTrue(rows.isEmpty()) }
        compose.onNodeWithText("No custom headers").assertExists()
    }
    @Test fun maintenanceStartsAndEndsFromTheServiceCard() {
        val now = 1_800_000_000_000L
        var until by mutableLongStateOf(0)
        var hours by mutableIntStateOf(0)
        show { Column(Modifier.padding(16.dp)) { MaintenanceControls(until, now, hours) { end, duration -> until = end; hours = duration } } }
        compose.onNodeWithText("4h").performClick()
        compose.runOnIdle { assertEquals(now + 4 * 3_600_000L, until) }
        screenshot("maintenance-dark")
        compose.onNodeWithText("4h").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(0, until) }
    }
    @Test fun storedMaintenanceChoiceStaysSelectedAsTheWindowCountsDown() {
        val now = 1_800_000_000_000L
        var until by mutableLongStateOf(now + 30 * 60_000)
        var hours by mutableIntStateOf(24)
        show { Column(Modifier.padding(16.dp)) { MaintenanceControls(until, now, hours) { end, duration -> until = end; hours = duration } } }
        compose.onNodeWithText("24h").assertIsSelected()
        compose.onNodeWithText("1h").assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(now + 3_600_000, until); assertEquals(1, hours) }
        compose.onNodeWithText("1h").performClick().assertIsNotSelected()
        compose.runOnIdle { assertEquals(0, until); assertEquals(0, hours) }
    }
    @Test fun savingEditsPreservesExistingMaintenanceAndHeaderRows() {
        val service = ServiceEntry(id = 1, name = "API", url = "https://example.com/", intervalSeconds = 300, maintenanceUntil = System.currentTimeMillis() + 3_600_000, maintenanceHours = 1)
        var saved: ServiceEntry? = null
        val headers = object : HeaderSecrets {
            var values: Map<String, String> = emptyMap()
            override fun encrypt(headers: Map<String, String>): String { values = headers; return "encrypted-test" }
            override fun decrypt(value: String): Map<String, String> = values
        }
        show { ServiceEditor(service, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, headerSecrets = headers, onSave = { saved = it }) }
        compose.onNodeWithText("Maintenance").assertDoesNotExist()
        compose.onNodeWithText("Authentication & headers").performScrollTo().performClick()
        compose.onNodeWithText("Add header").performScrollTo().performClick()
        compose.onNodeWithText("Header name").performScrollTo().performTextInput("X-Test")
        compose.onNodeWithText("Value").performScrollTo().performTextInput("hello")
        screenshot("custom-headers-dark")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(service.maintenanceUntil, saved!!.maintenanceUntil)
        assertEquals(service.maintenanceHours, saved!!.maintenanceHours)
        assertEquals(mapOf("X-Test" to "hello"), headers.values)
    }
    @Test fun phoneResponseEditorPreview() {
        val service = ServiceEntry(id = 1, name = "Production API", url = "https://api.example.com/health", intervalSeconds = 300, bodyType = "YAML", expectedBody = "status: ready\nchecks:\n  database: true\n  cache: true\nversion: 3")
        show { ServiceEditor(service, onCancel = {}, onSave = {}) }
        compose.onNodeWithText("Response rules").performScrollTo().performClick()
        compose.onNodeWithTag("Expected response").performScrollTo()
        waitForHighlight()
        compose.mainClock.advanceTimeBy(300)
        compose.waitUntil(10_000) { compose.onNodeWithText("Save").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText("Ignores formatting and key order. Array order and value types still matter.").performScrollTo()
        screenshot("phone-response-editor-dark")
        compose.onNodeWithText("Expand").performScrollTo().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Editor options").performScrollTo().performClick()
        compose.onNodeWithText("Wrap lines").performClick()
        compose.runOnIdle { assertFalse(editors().single().isWordwrap) }
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun compactToolbarKeepsActionsVisibleOnSmallPhones() {
        show { Column(Modifier.padding(32.dp)) { BodyEditor("Expected response", "{\"ok\":true}", "JSON") } }
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Redo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Format").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand").assertIsDisplayed().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val root = requireNotNull(view).rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/outputs/editor-preview/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
