package com.pocketupwatch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator

internal data class DisplayFold(val bounds: IntRect, val vertical: Boolean)
internal data class AdaptiveWindow(val width: Dp = 0.dp, val height: Dp = 0.dp, val fold: DisplayFold? = null) {
    // Keep the existing phone presentation, including ordinary phone landscape windows.
    val tablet: Boolean get() = width >= 600.dp && height >= 600.dp
    val workspace: Boolean get() = tablet && (width >= 672.dp || fold != null)
}
internal val LocalAdaptiveWindow = staticCompositionLocalOf { AdaptiveWindow() }
internal val LocalFoldOverride = staticCompositionLocalOf<DisplayFold?> { null }

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}

@Composable internal fun AdaptiveRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Window metrics exclude keyboard resizing, preventing a tablet from changing modes as the IME opens.
    val windowHeight = remember(activity, configuration) { activity?.let { WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(it).bounds.height() } }
    val flow = remember(activity) { activity?.let { WindowInfoTracker.getOrCreate(context).windowLayoutInfo(it) } }
    val info = flow?.collectAsStateWithLifecycle<WindowLayoutInfo?>(initialValue = null)?.value
    val feature = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
    val fold = LocalFoldOverride.current ?: feature?.let {
        DisplayFold(IntRect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom), it.orientation == FoldingFeature.Orientation.VERTICAL)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = windowHeight?.let { with(density) { it.toDp() } }?.coerceAtLeast(maxHeight) ?: maxHeight
        CompositionLocalProvider(LocalAdaptiveWindow provides AdaptiveWindow(maxWidth, height, fold), content = content)
    }
}

internal data class PanePlan(val primary: IntRect?, val secondary: IntRect)
internal fun panePlan(width: Int, height: Int, preferred: Int, gap: Int, fold: DisplayFold?, origin: Offset = Offset.Zero): PanePlan {
    if (fold != null) {
        val b = fold.bounds
        if (fold.vertical) {
            val left = (b.left - origin.x).toInt().coerceIn(0, width)
            val right = (b.right - origin.x).toInt().coerceIn(0, width)
            if (left > 0 && right < width) return PanePlan(IntRect(0, 0, (left - gap / 2).coerceAtLeast(0), height), IntRect((right + gap / 2).coerceAtMost(width), 0, width, height))
        } else {
            val top = (b.top - origin.y).toInt().coerceIn(0, height)
            val bottom = (b.bottom - origin.y).toInt().coerceIn(0, height)
            if (top > 0 && bottom < height) return PanePlan(IntRect(0, 0, width, (top - gap / 2).coerceAtLeast(0)), IntRect(0, (bottom + gap / 2).coerceAtMost(height), width, height))
        }
    }
    val first = preferred.coerceIn(0, (width - gap).coerceAtLeast(0))
    return PanePlan(IntRect(0, 0, first, height), IntRect((first + gap).coerceAtMost(width), 0, width, height))
}

/** Window-relative hinge coordinates are translated to this container before placing either pane. */
@Composable internal fun AdaptivePanes(
    modifier: Modifier = Modifier, primaryWidth: Dp = 320.dp,
    primary: @Composable () -> Unit, secondary: @Composable () -> Unit
) {
    val window = LocalAdaptiveWindow.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(content = {
        Box(Modifier.testTag("primary-pane").fillMaxSize().clipToBounds()) { primary() }
        Box(Modifier.testTag("secondary-pane").fillMaxSize().clipToBounds()) { secondary() }
    }, modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) { children, constraints ->
        val plan = panePlan(constraints.maxWidth, constraints.maxHeight, with(density) { primaryWidth.roundToPx() }, with(density) { 16.dp.roundToPx() }, window.fold, origin)
        val first = plan.primary ?: IntRect.Zero
        val a = children[0].measure(Constraints.fixed(first.width, first.height))
        val b = children[1].measure(Constraints.fixed(plan.secondary.width, plan.secondary.height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (plan.primary != null) a.place(first.left, first.top)
            b.place(plan.secondary.left, plan.secondary.top)
        }
    }
}

/** Cards use two readable columns only when this pane has enough room; narrow content is unchanged. */
@Composable internal fun AdaptiveSections(
    modifier: Modifier = Modifier, spacing: Dp = 12.dp, pairedRows: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
    val tablet = LocalAdaptiveWindow.current.tablet
    BoxWithConstraints(modifier) {
        val columns = tablet && maxWidth >= 720.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (!columns) content()
            else {
                val scope = this
                Layout(content = { scope.content() }, modifier = Modifier.fillMaxWidth().testTag("section-columns")) { children, constraints ->
                    val gutter = spacing.roundToPx()
                    val width = (constraints.maxWidth - gutter) / 2
                    val heights = intArrayOf(0, 0)
                    val placed = if (pairedRows) {
                        var y = 0
                        children.chunked(2).flatMap { row ->
                            val measured = row.map { it.measure(Constraints.fixedWidth(width)) }
                            val result = measured.mapIndexed { index, child -> Triple(child, index * (width + gutter), y) }
                            y += measured.maxOf { it.height } + gutter
                            heights[0] = y
                            result
                        }
                    } else children.map { child ->
                        val column = if (heights[0] <= heights[1]) 0 else 1
                        val placeable = child.measure(Constraints.fixedWidth(width))
                        val y = heights[column]
                        heights[column] += placeable.height + gutter
                        Triple(placeable, column * (width + gutter), y)
                    }
                    layout(constraints.maxWidth, ((heights.maxOrNull() ?: 0) - gutter).coerceAtLeast(0)) { placed.forEach { (child, x, y) -> child.placeRelative(x, y) } }
                }
            }
        }
    }
}
