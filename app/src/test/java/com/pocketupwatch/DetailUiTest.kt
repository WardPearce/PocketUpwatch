package com.pocketupwatch

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DetailUiTest {
    @get:Rule val compose = createComposeRule()
    @After fun resetProgress() { MonitorService.checksInProgress.value = emptySet() }

    @Test fun checkNowShowsProgressUntilServiceFinishesAndPreventsDuplicateRequests() {
        val service = ServiceEntry(id = 42, name = "Health", url = "https://example.com/health", intervalSeconds = 300)
        val vm = MonitorViewModel(ApplicationProvider.getApplicationContext<Application>())
        MonitorService.checksInProgress.value = emptySet()
        compose.setContent { MaterialTheme { Detail(service, vm, Modifier, onEdit = {}, onDeleted = {}) } }
        compose.onNodeWithTag("service-status").assertExists()
        compose.onNodeWithTag("service-target").assertExists()
        compose.onNodeWithTag("service-schedule").assertExists()
        compose.onNodeWithTag("service-actions").assertExists()
        compose.onNodeWithText("Next check").assertExists()
        compose.onNodeWithText("Check now").performScrollTo().performClick()
        compose.onNodeWithTag("check-progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Checking…").assertIsNotEnabled()
        compose.runOnIdle { MonitorService.checksInProgress.value = emptySet() }
        compose.onNodeWithText("Check now").assertIsEnabled()
        compose.onNodeWithTag("check-progress", useUnmergedTree = true).assertDoesNotExist()
    }
}
