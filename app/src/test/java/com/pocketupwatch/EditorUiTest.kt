package com.pocketupwatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import kotlin.math.abs
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditorUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun existingAny2xxMonitorCanBeSavedWithoutConvertingTo200() {
        val existing = ServiceEntry(id = 1, name = "Health", url = "https://example.com/health", intervalSeconds = 300, expectedCode = 0)
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, onSave = { saved = it }) } }
        compose.onNodeWithText("Accept any 2xx status").assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(0, saved!!.expectedCode)
        assertEquals(existing.url, saved!!.url)
    }
    @Test fun invalidJsonExpectationCannotBeSaved() {
        val existing = ServiceEntry(id = 2, name = "Health", url = "https://example.com", intervalSeconds = 300, bodyType = "JSON", expectedBody = "{")
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, onSave = { fail("Invalid JSON must not be saved") }) } }
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Invalid JSON expectation or JSON path").assertExists()
    }
    @Test fun intervalSliderSavesHumanReadableSelection() {
        val existing = ServiceEntry(id = 3, name = "Health", url = "https://example.com/health", intervalSeconds = 300)
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, onSave = { saved = it }) } }
        compose.onNodeWithTag("interval-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.onNodeWithText("Check interval: 2hours").assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(7200, saved!!.intervalSeconds)
    }
    @Test fun editingPreservesCustomIntervalAndKeepsProtocolInline() {
        val existing = ServiceEntry(id = 4, name = "Health", url = "https://example.com/health", intervalSeconds = 750)
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, onSave = { saved = it }) } }
        val protocol = compose.onNodeWithContentDescription("Protocol").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithText("Host, path and query").fetchSemanticsNode().boundsInRoot
        assertTrue(protocol.right <= host.left)
        assertTrue(abs(protocol.center.y - host.center.y) < 4f)
        compose.onNodeWithText("Check interval: 12min 30sec").assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(750, saved!!.intervalSeconds)
    }

}
