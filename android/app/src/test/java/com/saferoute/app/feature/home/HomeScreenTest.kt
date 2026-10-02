// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Home layout and the emergency dialog, without navigation or a ViewModel. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()

    private fun string(id: Int): String = context.getString(id)

    @Composable
    private fun Home(
        dialog: EmergencyDialogState = EmergencyDialogState.Hidden,
        showDebugDetails: Boolean = true,
        fontScale: Float = 1f,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                HomeScreen(
                    emergencyDialog = dialog,
                    onSearchClick = { events += "search" },
                    onSettingsClick = { events += "settings" },
                    onEmergencyClick = { events += "emergency" },
                    onCallEmergency = { events += "call" },
                    onDismissEmergencyDialog = { events += "dismiss" },
                    showDebugDetails = showDebugDetails,
                )
            }
        }
    }

    private fun searchPill() = compose.onNodeWithText(string(R.string.search_hint))
    private fun settingsButton() = compose.onNodeWithContentDescription(string(R.string.settings_title))
    private fun myLocation() =
        compose.onNodeWithContentDescription(string(R.string.map_control_my_location_unavailable))
    private fun layers() =
        compose.onNodeWithContentDescription(string(R.string.map_control_layers_unavailable))
    private fun emergencyButton() = compose.onNodeWithText(string(R.string.emergency_button_label))
    private fun sheetHandle() =
        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description))

    @Test
    fun `shows the search pill, map controls, emergency button and sheet`() {
        compose.setContent { Home() }

        searchPill().assertIsDisplayed()
        settingsButton().assertIsDisplayed()
        myLocation().assertIsDisplayed().assertIsNotEnabled()
        layers().assertIsDisplayed().assertIsNotEnabled()
        emergencyButton().assertIsDisplayed()
        sheetHandle().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertIsDisplayed()
    }

    @Test
    fun `controls report their events and disabled map controls report nothing`() {
        compose.setContent { Home() }

        searchPill().performClick()
        settingsButton().performClick()
        myLocation().performClick()
        layers().performClick()
        emergencyButton().performClick()

        assertEquals(listOf("search", "settings", "emergency"), events)
    }

    @Test
    fun `talkback order is search, map controls, emergency, sheet`() {
        compose.setContent { Home() }

        fun inGroup(index: Float): SemanticsMatcher {
            val group = SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, index)
            return group or hasAnyAncestor(group)
        }

        assertTrue(HomeTraversal.Search < HomeTraversal.MapControls)
        assertTrue(HomeTraversal.MapControls < HomeTraversal.Emergency)
        assertTrue(HomeTraversal.Emergency < HomeTraversal.Sheet)
        searchPill().assert(inGroup(HomeTraversal.Search))
        settingsButton().assert(inGroup(HomeTraversal.Search))
        myLocation().assert(inGroup(HomeTraversal.MapControls))
        layers().assert(inGroup(HomeTraversal.MapControls))
        emergencyButton().assert(inGroup(HomeTraversal.Emergency))
        sheetHandle().assert(inGroup(HomeTraversal.Sheet))
    }

    @Test
    fun `the map placeholder label is shown in debug builds only`() {
        compose.setContent { Home(showDebugDetails = true) }
        compose.onNodeWithText(string(R.string.map_placeholder_label)).assertIsDisplayed()
    }

    @Test
    fun `a release build shows no developer note on the map`() {
        compose.setContent { Home(showDebugDetails = false) }
        compose.onNodeWithText(string(R.string.map_placeholder_label)).assertDoesNotExist()
        emergencyButton().assertIsDisplayed()
    }

    @Test
    fun `at 200 percent font the key controls are still shown and big enough`() {
        compose.setContent { Home(fontScale = 2f) }

        listOf(searchPill(), settingsButton(), myLocation(), layers(), emergencyButton(), sheetHandle())
            .forEach { it.assertIsDisplayed().assertMinTouchTarget() }
    }

    @Test
    fun `the dialog says what it is and offers to open the dialer`() {
        compose.setContent { Home(dialog = EmergencyDialogState.OfferDialer) }

        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.emergency_dialog_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.emergency_dialog_dialer_hint)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()

        assertEquals(listOf("call", "dismiss"), events)
    }

    @Test
    fun `without a dialer the dialog shows the number and no call button`() {
        compose.setContent { Home(dialog = EmergencyDialogState.DialerUnavailable) }

        val fallback = string(R.string.emergency_dialog_no_dialer)
        assertTrue(fallback.contains("112"))
        compose.onNodeWithText(fallback).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.emergency_dialog_close)).performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun `no dialog is shown while hidden`() {
        compose.setContent { Home(dialog = EmergencyDialogState.Hidden) }
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `home and the dialog are in bengali when the language is bengali`() {
        compose.setContent { Home(dialog = EmergencyDialogState.OfferDialer) }

        // Literal expected values: this must fail if the app fell back to English.
        compose.onNodeWithText("কোথায় যাবেন?").assertIsDisplayed()
        compose.onNodeWithText("112-এ কল করুন").assertIsDisplayed()
        compose.onNodeWithText("SafeRoute কোনো জরুরি পরিষেবা নয়।").assertIsDisplayed()
    }
}
