// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The three detents of the bottom sheet, reached by accessibility action, tap and drag. */
@RunWith(AndroidJUnit4::class)
class SafeRouteBottomSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    // Smaller than Robolectric's default screen (320 x 470 dp), so the size is not clamped.
    private val containerHeight = 400.dp
    private val body = "sheet body"

    private lateinit var state: SafeRouteSheetState

    private fun setSheet(initial: SheetDetent = SheetDetent.Peek) {
        compose.setContent {
            SafeRouteTheme {
                state = rememberSafeRouteSheetState(initial)
                Box(Modifier.size(width = 300.dp, height = containerHeight)) {
                    SafeRouteBottomSheet(state = state) { Text(body) }
                }
            }
        }
    }

    private fun handle() =
        compose.onNodeWithContentDescription(context.getString(R.string.sheet_handle_description))

    private fun assertState(detent: SheetDetent, descriptionRes: Int) {
        compose.waitForIdle()
        assertEquals(detent, state.currentDetent)
        handle().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(descriptionRes),
            ),
        )
    }

    @Test
    fun `starts at peek with the sheet top one peek height above the bottom`() {
        setSheet()

        assertState(SheetDetent.Peek, R.string.sheet_state_peek)
        // Robolectric has no system bars, so the insets are zero.
        handle().assertTopPositionInRootIsEqualTo(containerHeight - SafeRouteSheetDefaults.PeekHeight)
        handle().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Collapse))
    }

    @Test
    fun `expand and collapse actions step through peek, half and full`() {
        setSheet()

        handle().performSemanticsAction(SemanticsActions.Expand)
        assertState(SheetDetent.Half, R.string.sheet_state_half)
        handle().assertTopPositionInRootIsEqualTo(containerHeight * SafeRouteSheetDefaults.HalfFraction)

        handle().performSemanticsAction(SemanticsActions.Expand)
        assertState(SheetDetent.Full, R.string.sheet_state_full)
        handle().assertTopPositionInRootIsEqualTo(SafeRouteSheetDefaults.FullTopGap)
        handle().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Expand))

        handle().performSemanticsAction(SemanticsActions.Collapse)
        assertState(SheetDetent.Half, R.string.sheet_state_half)

        handle().performSemanticsAction(SemanticsActions.Collapse)
        assertState(SheetDetent.Peek, R.string.sheet_state_peek)
    }

    @Test
    fun `tapping the handle cycles peek, half, full and back to peek`() {
        setSheet()

        handle().performClick()
        assertState(SheetDetent.Half, R.string.sheet_state_half)
        handle().performClick()
        assertState(SheetDetent.Full, R.string.sheet_state_full)
        handle().performClick()
        assertState(SheetDetent.Peek, R.string.sheet_state_peek)
    }

    @Test
    fun `dragging moves the sheet between detents`() {
        setSheet(SheetDetent.Half)
        assertState(SheetDetent.Half, R.string.sheet_state_half)

        compose.onNodeWithText(body).performTouchInput { swipeUp(startY = centerY, endY = centerY - 100f) }
        assertState(SheetDetent.Full, R.string.sheet_state_full)

        compose.onNodeWithText(body).performTouchInput { swipeDown(startY = centerY, endY = centerY + 100f) }
        compose.waitForIdle()
        assertEquals(SheetDetent.Half, state.currentDetent)
    }

    @Test
    fun `can start at any detent`() {
        setSheet(SheetDetent.Full)

        assertState(SheetDetent.Full, R.string.sheet_state_full)
        handle().assertTopPositionInRootIsEqualTo(SafeRouteSheetDefaults.FullTopGap)
    }
}
