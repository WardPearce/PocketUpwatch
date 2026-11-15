package com.pocketupwatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    @Test fun pastedWebUrlsSelectTheirProtocolAndKeepTheCompleteTarget() {
        val saved = mutableListOf<ServiceEntry>()
        compose.setContent { MaterialTheme { ServiceEditor(null, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = { saved.add(it) }) } }
        compose.onNodeWithText("Name").performTextInput("Example")
        val cases = listOf(
            Triple("http://example.com", "HTTP", "example.com"),
            Triple("  HTTPS://Example.com:8443/Health?next=http://other.example/a  ", "HTTPS", "Example.com:8443/Health?next=http://other.example/a")
        )
        cases.forEachIndexed { index, (input, protocol, target) ->
            compose.onNodeWithTag("monitor-target").performScrollTo().performTextReplacement(input)
            compose.onNodeWithContentDescription("Protocol").assertTextContains(protocol)
            assertEquals(target, compose.onNodeWithTag("monitor-target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { saved.size == index + 1 }
        }
        assertEquals("http://example.com/", saved[0].url)
        assertEquals("https://example.com:8443/Health?next=http://other.example/a", saved[1].url)
        // A URL embedded in a query must not change the selected protocol or strip part of the target.
        val bare = "example.com/health?redirect=http://other.example"
        compose.onNodeWithTag("monitor-target").performTextReplacement(bare)
        compose.onNodeWithContentDescription("Protocol").assertTextContains("HTTPS")
        assertEquals(bare, compose.onNodeWithTag("monitor-target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun webProtocolsAreDetectedWhenTypedOneCharacterAtATime() {
        compose.setContent { MaterialTheme { ServiceEditor(null, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = {}) } }
        for (protocol in listOf("http", "https")) {
            compose.onNodeWithTag("monitor-target").performTextClearance()
            "$protocol://".forEach { compose.onNodeWithTag("monitor-target").performTextInput(it.toString()) }
            compose.onNodeWithContentDescription("Protocol").assertTextContains(protocol.uppercase())
            assertEquals("", compose.onNodeWithTag("monitor-target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            "example.com".forEach { compose.onNodeWithTag("monitor-target").performTextInput(it.toString()) }
            assertEquals("example.com", compose.onNodeWithTag("monitor-target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }

    @Test fun existingAny2xxMonitorCanBeSavedWithoutConvertingTo200() {
        val existing = ServiceEntry(id = 1, name = "Health", url = "https://example.com/health", intervalSeconds = 300, expectedCode = 0)
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = { saved = it }) } }
        compose.onNodeWithText("Response rules").performScrollTo().performClick()
        compose.onNodeWithText("Accept any 2xx status").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(0, saved!!.expectedCode)
        assertEquals(existing.url, saved!!.url)
    }
    @Test fun invalidJsonExpectationCannotBeSaved() {
        val existing = ServiceEntry(id = 2, name = "Health", url = "https://example.com", intervalSeconds = 300, bodyType = "JSON", expectedBody = "{")
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, onSave = { fail("Invalid JSON must not be saved") }) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Invalid JSON expectation or JSON path").assertExists()
    }
    @Test fun intervalSliderSavesHumanReadableSelection() {
        val existing = ServiceEntry(id = 3, name = "Health", url = "https://example.com/health", intervalSeconds = 300)
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = { saved = it }) } }
        compose.onNodeWithTag("interval-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.onNodeWithText("Check interval: 2hours").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(7200, saved!!.intervalSeconds)
    }
    @Test fun editingPreservesCustomIntervalAndKeepsProtocolInline() {
        val existing = ServiceEntry(id = 4, name = "Health", url = "https://example.com/health", intervalSeconds = 750)
        var saved: ServiceEntry? = null
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = { saved = it }) } }
        val protocol = compose.onNodeWithContentDescription("Protocol").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithText("Host, path and query").fetchSemanticsNode().boundsInRoot
        assertTrue(protocol.right <= host.left)
        assertTrue(abs(protocol.center.y - host.center.y) < 4f)
        compose.onNodeWithText("Check interval: 12min 30sec").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(750, saved!!.intervalSeconds)
    }

    @Test fun saveChecksFirstShowsLoadingAndUsesOneFailureByDefault() {
        val started = CompletableDeferred<ServiceEntry>()
        val result = CompletableDeferred<ProbeResult>()
        var saved: ServiceEntry? = null
        val probe = Probe { service -> started.complete(service); result.await() }
        compose.setContent { MaterialTheme { ServiceEditor(null, onCancel = {}, probe = probe, onSave = { saved = it }) } }
        compose.onNodeWithText("Name").performScrollTo().performTextInput("Health")
        compose.onNodeWithText("Host, path and query").performScrollTo().performTextInput("example.com/health")
        compose.onNodeWithText("Test before saving").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { started.isCompleted }
        compose.onNodeWithTag("save-progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Checking…").assertIsNotEnabled()
        assertNull(saved)
        result.complete(ProbeResult(true, "HTTP 200"))
        compose.waitUntil(10_000) { saved != null }
        assertEquals(1, saved!!.failureThreshold)
    }
    @Test fun failedSaveShowsHttpErrorAndCanBeRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200))
            val existing = ServiceEntry(id = 5, name = "Health", url = server.url("/health").toString(), intervalSeconds = 300)
            var saved: ServiceEntry? = null
            compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = ProbeEngine({ emptyMap() }), onSave = { saved = it }) } }
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Check failed: HTTP 503").fetchSemanticsNodes().isNotEmpty() }
            assertNull(saved)
            compose.onNodeWithText("Save").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { saved != null }
            assertEquals(2, server.requestCount)
            assertEquals(existing.url, saved!!.url)
        }
    }
    @Test fun unknownCheckKeepsEditsAndDoesNotSave() {
        val existing = ServiceEntry(id = 6, name = "Health", url = "https://example.com", intervalSeconds = 300)
        var saved = false
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = Probe { ProbeResult(null, "Credentials unavailable") }, onSave = { saved = true }) } }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Check failed: Credentials unavailable").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(saved)
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Health").assertExists()
    }
    @Test fun unexpectedCheckErrorRestoresSaveButton() {
        val existing = ServiceEntry(id = 7, name = "Health", url = "https://example.com", intervalSeconds = 300)
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = Probe { throw java.io.IOException("test") }, onSave = { fail("Must not save") }) } }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not test the monitor. Please try again.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun saveErrorIsShownAndAllowsAnotherAttempt() {
        val existing = ServiceEntry(id = 8, name = "Health", url = "https://example.com", intervalSeconds = 300)
        compose.setContent { MaterialTheme { ServiceEditor(existing, onCancel = {}, probe = Probe { ProbeResult(true, "HTTP 200") }, onSave = { throw java.io.IOException("test") }) } }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not save the monitor. Please try again.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").assertIsEnabled()
    }

}
