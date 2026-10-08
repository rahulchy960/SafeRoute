// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteShapeTokens
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The three resting positions ("detents") of [SafeRouteBottomSheet], shortest first. */
enum class SheetDetent {
    /** Only the top of the sheet shows; the map has almost the whole screen. */
    Peek,

    /** The sheet covers the lower half of the screen. */
    Half,

    /** The sheet reaches up to just below the status bar. */
    Full,
    ;

    internal fun taller(): SheetDetent? = entries.getOrNull(ordinal + 1)

    internal fun shorter(): SheetDetent? = entries.getOrNull(ordinal - 1)
}

/** On the sheet's surface, so that layout tests can ask what the sheet currently covers. */
const val SheetSurfaceTag = "sheet-surface"

/** Sizes and motion of [SafeRouteBottomSheet]. */
object SafeRouteSheetDefaults {
    /** How much of the sheet shows at [SheetDetent.Peek], not counting the navigation bar. */
    val PeekHeight: Dp = 148.dp

    /** Share of the available height the sheet covers at [SheetDetent.Half]. */
    const val HalfFraction: Float = 0.5f

    /** Gap kept between the status bar and the sheet at [SheetDetent.Full]. */
    val FullTopGap: Dp = 8.dp

    /**
     * A spring without bounce. A spring has no fixed duration; with this stiffness the sheet
     * looks settled after roughly 250 to 300 ms, and unlike a timed animation it carries on
     * smoothly from the speed of the finger that let go.
     */
    val Motion: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 600f,
    )
}

/**
 * Holds which detent the sheet is at and moves it. Create one with
 * [rememberSafeRouteSheetState] and keep it in the screen that owns the sheet (state
 * hoisting), so that the screen can read the position or move the sheet from outside.
 */
@Stable
class SafeRouteSheetState(initialDetent: SheetDetent = SheetDetent.Peek) {

    internal val draggable = AnchoredDraggableState(initialDetent)

    /** The detent the sheet last came to rest at. */
    val currentDetent: SheetDetent
        get() = draggable.settledValue

    /** Where the sheet is heading while it is dragged or animating; otherwise [currentDetent]. */
    val targetDetent: SheetDetent
        get() = draggable.targetValue

    /** Animates to [detent]. Suspends until the sheet has arrived (call it from a coroutine). */
    suspend fun animateTo(detent: SheetDetent) {
        draggable.animateTo(detent, SafeRouteSheetDefaults.Motion)
    }

    /** One step taller. Does nothing at [SheetDetent.Full]. */
    suspend fun expand() {
        currentDetent.taller()?.let { animateTo(it) }
    }

    /** One step shorter. Does nothing at [SheetDetent.Peek]. */
    suspend fun collapse() {
        currentDetent.shorter()?.let { animateTo(it) }
    }

    companion object {
        /** Lets the detent survive rotation and the system reclaiming the app's memory. */
        val Saver: Saver<SafeRouteSheetState, String> = Saver(
            save = { it.currentDetent.name },
            restore = { SafeRouteSheetState(SheetDetent.valueOf(it)) },
        )
    }
}

/** Creates a [SafeRouteSheetState] that survives recomposition and configuration changes. */
@Composable
fun rememberSafeRouteSheetState(
    initialDetent: SheetDetent = SheetDetent.Peek,
): SafeRouteSheetState = rememberSaveable(saver = SafeRouteSheetState.Saver) {
    SafeRouteSheetState(initialDetent)
}

/**
 * A persistent bottom sheet that rests at three heights ([SheetDetent]). It never hides: on
 * the map screen it is always at least peeking.
 *
 * Give it the same bounds as the area it slides over (normally `Modifier.fillMaxSize()` on top
 * of the map). Touches outside the sheet fall through to whatever is below.
 *
 * Why not Material's `BottomSheetScaffold`: in Material 3 1.4 it is still an experimental API
 * and has two visible positions, not three (ADR 0008, "stable APIs only"). This sheet is built
 * on Compose Foundation's stable `anchoredDraggable`.
 *
 * Accessibility: the handle at the top can be tapped, and exposes expand and collapse actions,
 * so the sheet is fully usable without a drag gesture.
 *
 * @param state Position holder; hoist it to control the sheet from the screen.
 * @param peekHeight Visible height at [SheetDetent.Peek], on top of the navigation bar inset.
 * @param content The sheet body, laid out in a column below the handle.
 */
@Composable
fun SafeRouteBottomSheet(
    modifier: Modifier = Modifier,
    state: SafeRouteSheetState = rememberSafeRouteSheetState(),
    peekHeight: Dp = SafeRouteSheetDefaults.PeekHeight,
    content: @Composable ColumnScope.() -> Unit,
) {
    // animateTo is a suspend function; a click handler is not, so it starts a coroutine that
    // is cancelled automatically when the sheet leaves the screen.
    val scope = rememberCoroutineScope()
    val moveTo: (SheetDetent) -> Unit = { target -> scope.launch { state.animateTo(target) } }
    val density = LocalDensity.current
    val navigationBarHeight = WindowInsets.navigationBars.getBottom(density)
    val statusBarHeight = WindowInsets.statusBars.getTop(density)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerHeight = constraints.maxHeight.toFloat()
        val fullTop = statusBarHeight + with(density) { SafeRouteSheetDefaults.FullTopGap.toPx() }
        val peekTop = containerHeight - with(density) { peekHeight.toPx() } - navigationBarHeight
        val halfTop = containerHeight * (1f - SafeRouteSheetDefaults.HalfFraction)

        // Each anchor is the distance in pixels from the top of the container to the top of
        // the sheet. On a very short container the lower detents collapse onto the full one.
        val anchors = remember(containerHeight, fullTop, peekTop, halfTop) {
            DraggableAnchors {
                SheetDetent.Peek at peekTop.coerceAtLeast(fullTop)
                SheetDetent.Half at halfTop.coerceAtLeast(fullTop)
                SheetDetent.Full at fullTop
            }
        }
        // Not during composition: the anchors are state that the drag gesture reads.
        SideEffect {
            state.draggable.updateAnchors(anchors, newTarget = state.draggable.targetValue)
        }

        Surface(
            modifier = Modifier
                .offset {
                    // The offset is unknown (NaN) only until the first anchors are applied.
                    val top = state.draggable.offset.takeUnless { it.isNaN() }
                        ?: anchors.positionOf(state.currentDetent)
                    IntOffset(x = 0, y = top.roundToInt())
                }
                .fillMaxWidth()
                .height(with(density) { (containerHeight - fullTop).coerceAtLeast(0f).toDp() })
                .testTag(SheetSurfaceTag)
                .anchoredDraggable(
                    state = state.draggable,
                    orientation = Orientation.Vertical,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = state.draggable,
                        animationSpec = SafeRouteSheetDefaults.Motion,
                    ),
                ),
            shape = SafeRouteShapeTokens.SheetTop,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = SafeRouteTheme.elevation.sheet,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                val detent = state.currentDetent
                SheetHandle(
                    stateDescription = stringResource(detent.stateDescriptionRes()),
                    // A tap goes one step up and wraps round from the tallest to the shortest.
                    onClick = { moveTo(detent.taller() ?: SheetDetent.Peek) },
                    onExpand = detent.taller()?.let { target -> { moveTo(target) } },
                    onCollapse = detent.shorter()?.let { target -> { moveTo(target) } },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                content()
            }
        }
    }
}

private fun SheetDetent.stateDescriptionRes(): Int = when (this) {
    SheetDetent.Peek -> R.string.sheet_state_peek
    SheetDetent.Half -> R.string.sheet_state_half
    SheetDetent.Full -> R.string.sheet_state_full
}

@Composable
private fun SheetPreview(detent: SheetDetent, darkTheme: Boolean = false) {
    SafeRouteTheme(darkTheme = darkTheme) {
        Box(Modifier.fillMaxSize()) {
            SafeRouteBottomSheet(state = rememberSafeRouteSheetState(detent)) {
                Text(
                    text = stringResource(R.string.not_emergency_service),
                    modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.md),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Preview(name = "Sheet 1 Peek", showBackground = true, heightDp = 640)
@Composable
private fun SheetPeekPreview() = SheetPreview(SheetDetent.Peek)

@Preview(name = "Sheet 2 Half", showBackground = true, heightDp = 640)
@Composable
private fun SheetHalfPreview() = SheetPreview(SheetDetent.Half)

@Preview(name = "Sheet 3 Full", showBackground = true, heightDp = 640)
@Composable
private fun SheetFullPreview() = SheetPreview(SheetDetent.Full)

@Preview(name = "Sheet 4 Half, dark", showBackground = true, heightDp = 640)
@Composable
private fun SheetHalfDarkPreview() = SheetPreview(SheetDetent.Half, darkTheme = true)

@Preview(
    name = "Sheet 5 Half, Bengali, font 200%",
    showBackground = true,
    heightDp = 640,
    locale = "bn",
    fontScale = 2f,
)
@Composable
private fun SheetHalfBengaliLargeFontPreview() = SheetPreview(SheetDetent.Half)
