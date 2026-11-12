package com.pocketupwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
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
class ServiceWidgetPickerTest {
    @get:Rule val compose = createComposeRule()
    private val services = listOf(
        ServiceEntry(id = 1, name = "Vaultwarden", url = "https://user:secret@example.com/health?token=private", intervalSeconds = 300),
        ServiceEntry(id = 2, name = "Home router", url = "http://192.168.1.1", intervalSeconds = 300)
    )

    @Test fun selectionEnablesSavingAndShowsNoCredentials() {
        var selected by mutableLongStateOf(0)
        var saved = 0L
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            MaterialTheme { ServiceWidgetPicker(services, selected, false, false, null, { selected = it }, {}, {}, { saved = selected }) }
        }
        compose.onNodeWithText("Add widget").assertIsNotEnabled()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("token=private", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("widget-service-1").performClick().assertIsSelected()
        compose.onNodeWithText("Add widget").assertIsEnabled()
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val path = File("build/outputs/editor-preview/service-widget-picker.png").apply { parentFile!!.mkdirs() }
                path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        compose.onNodeWithTag("widget-service-2").performClick().assertIsSelected()
        compose.onNodeWithText("Add widget").performClick()
        assertEquals(2L, saved)
    }

    @Test fun emptyPickerOffersTheAppAndCanBeCancelled() {
        var opened = false
        var cancelled = false
        compose.setContent { MaterialTheme { ServiceWidgetPicker(emptyList(), 0, false, false, null, {}, { cancelled = true }, { opened = true }, {}) } }
        compose.onNodeWithText("No services yet").assertIsDisplayed()
        compose.onNodeWithText("Add widget").assertIsNotEnabled()
        compose.onNodeWithText("Open Pocket Upwatch").performClick()
        assertTrue(opened)
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    @Test fun reconfiguringKeepsSelectionAndDisablesActionsWhileSaving() {
        compose.setContent { MaterialTheme { ServiceWidgetPicker(services, 2, true, true, null, {}, {}, {}, {}) } }
        compose.onNodeWithTag("widget-service-2").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Save widget").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }
}
