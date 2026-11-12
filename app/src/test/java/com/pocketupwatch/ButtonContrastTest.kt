package com.pocketupwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import java.io.File
import kotlin.math.roundToInt
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
class ButtonContrastTest {
    @get:Rule val compose = createComposeRule()
    private var view: View? = null

    @Test fun lightButtonsHaveVisibleBoundariesAcrossAccentColors() = verifyPalettes(false)
    @Test fun darkButtonsHaveVisibleBoundariesAcrossAccentColors() = verifyPalettes(true)

    private fun verifyPalettes(dark: Boolean) {
        // Representative Material dynamic-color roles, including the pale blue from the report.
        val accents = if (dark) listOf(
            Accent("blue", 0xffc0c6dd, 0xff404659, 0xffdce2f9),
            Accent("green", 0xffbdcbae, 0xff3e4a34, 0xffd9e7ca),
            Accent("rose", 0xffe3bdc6, 0xff5b3f48, 0xffffd9e2),
            Accent("amber", 0xffdbc3a1, 0xff54442a, 0xfff8dfbb)
        ) else listOf(
            Accent("blue", 0xff585e71, 0xffdce2f9, 0xff151b2c),
            Accent("green", 0xff56624b, 0xffd9e7ca, 0xff141e0d),
            Accent("rose", 0xff74565f, 0xffffd9e2, 0xff2b151d),
            Accent("amber", 0xff6d5c3f, 0xfff8dfbb, 0xff261a04)
        )
        val base = if (dark) darkColorScheme() else lightColorScheme()
        var colors by mutableStateOf(base)
        compose.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIconButton({}, Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Text("Button contrast", style = MaterialTheme.typography.headlineSmall)
                        }
                        DetailSection {
                            Text("Service actions", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppSecondaryButton({}, Modifier.weight(1f).testTag("edit")) { Text("Edit") }
                                AppButton({}, Modifier.weight(1f)) { Text("Check now") }
                            }
                        }
                        DetailSection {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Availability", style = MaterialTheme.typography.titleLarge)
                                AppIconButton({}, Modifier.testTag("fullscreen")) { Icon(Icons.Default.Fullscreen, "Fullscreen") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppPeriodButton("1h", false, Modifier.weight(1f).testTag("period")) {}
                                AppPeriodButton("24h", true, Modifier.weight(1f).testTag("selected-period")) {}
                            }
                        }
                        DetailSection {
                            AppDangerButton({}, Modifier.fillMaxWidth().testTag("delete")) { Text("Delete monitor") }
                            AppSecondaryButton({}, Modifier.fillMaxWidth().testTag("disabled"), enabled = false) { Text("Previous") }
                        }
                    }
                }
            }
        }
        val palettes = listOf("default" to base) + accents.map { accent ->
            accent.name to base.copy(secondary = Color(accent.border), secondaryContainer = Color(accent.fill), onSecondaryContainer = Color(accent.content))
        }
        for ((name, palette) in palettes) {
            compose.runOnIdle { colors = palette }
            compose.waitForIdle()
            compose.onNodeWithTag("disabled").assertIsNotEnabled()
            val bitmap = drawScreen()
            try {
                for (tag in listOf("edit", "back", "fullscreen", "period")) {
                    assertContrast(bitmap, tag, palette.secondary, palette.secondaryContainer, palette.onSecondaryContainer)
                }
                assertContrast(bitmap, "delete", palette.error, palette.errorContainer, palette.onErrorContainer)
                assertContrast(bitmap, "selected-period", palette.primary, palette.primary, palette.onPrimary)
                val path = File("build/outputs/editor-preview/button-contrast-${if (dark) "dark" else "light"}-$name.png")
                path.parentFile!!.mkdirs()
                path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    private fun drawScreen(): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun assertContrast(bitmap: Bitmap, tag: String, border: Color, fill: Color, content: Color) {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val left = bounds.left.roundToInt()
        val centerY = bounds.center.y.roundToInt()
        val actualBorder = bitmap.getPixel(left + 1, centerY)
        val actualFill = bitmap.getPixel(left + 12, centerY)
        val surroundingSurface = bitmap.getPixel(left - 2, centerY)
        assertEquals("$tag must draw its outline", border.toArgb(), actualBorder)
        assertEquals("$tag must keep its filled background", fill.toArgb(), actualFill)
        assertTrue("$tag boundary must contrast 3:1 with its surroundings", ColorUtils.calculateContrast(actualBorder, surroundingSurface) >= 3.0)
        assertTrue("$tag boundary must contrast 3:1 with its fill", actualBorder == actualFill || ColorUtils.calculateContrast(actualBorder, actualFill) >= 3.0)
        assertTrue("$tag label/icon must contrast 4.5:1 with its fill", ColorUtils.calculateContrast(content.toArgb(), actualFill) >= 4.5)
        val contentDrawn = (bounds.top.roundToInt() until bounds.bottom.roundToInt()).any { y ->
            (left until bounds.right.roundToInt()).any { x -> bitmap.getPixel(x, y) == content.toArgb() }
        }
        assertTrue("$tag must render its readable label/icon color", contentDrawn)
    }

    private data class Accent(val name: String, val border: Long, val fill: Long, val content: Long)
}
