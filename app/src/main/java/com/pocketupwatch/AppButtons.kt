package com.pocketupwatch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private val actionShape = RoundedCornerShape(8.dp)

@Composable private fun lightModeOutline(color: Color, enabled: Boolean = true): BorderStroke? {
    // Follow the rendered palette, including the app's manual theme override.
    return if (enabled && MaterialTheme.colorScheme.surface.luminance() > 0.5f) BorderStroke(1.dp, color) else null
}

@Composable internal fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = actionShape, contentPadding = contentPadding, content = content)
}

@Composable internal fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = actionShape, contentPadding = contentPadding,
        border = lightModeOutline(MaterialTheme.colorScheme.secondary, enabled),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer), content = content)
}

@Composable internal fun AppDangerButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 48.dp), shape = actionShape,
        border = lightModeOutline(MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), content = content)
}

@Composable internal fun AppIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val background = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val border = lightModeOutline(MaterialTheme.colorScheme.secondary, enabled)
    val outline = if (border != null) Modifier.border(border, actionShape) else Modifier
    IconButton(onClick, modifier.size(48.dp).clip(actionShape).background(background).then(outline), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer), content = content)
}

@Composable internal fun AppPeriodButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 48.dp), shape = actionShape, contentPadding = PaddingValues(horizontal = 8.dp),
        border = lightModeOutline(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)) { Text(label) }
}
