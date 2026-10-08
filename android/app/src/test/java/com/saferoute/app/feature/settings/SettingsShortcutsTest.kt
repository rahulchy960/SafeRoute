// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.emergency.FakeTileAdder
import com.saferoute.app.feature.emergency.TileAddResult
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Add the SOS tile" in Settings: the request, its three answers, and the manual way. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SettingsShortcutsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)

    private val events = mutableListOf<String>()
    private var notice: TileNotice? by mutableStateOf(null)

    @Composable
    private fun Settings(fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                SettingsScreen(
                    versionName = "9.8.7",
                    versionCode = 42,
                    onBack = {},
                    tileNotice = notice,
                    onAddTile = { events += "add" },
                    onTileNoticeDismiss = { events += "dismiss" },
                )
            }
        }
    }

    // --- the view model -----------------------------------------------------------------

    @Test
    fun `nothing is requested until the row is tapped, and each answer has its notice`() {
        val adder = FakeTileAdder()
        val viewModel = EmergencyShortcutsViewModel(adder)
        assertEquals(0, adder.requests)
        assertNull(viewModel.tileNotice.value)

        val expected = mapOf(
            TileAddResult.Added to TileNotice.Added,
            TileAddResult.AlreadyAdded to TileNotice.AlreadyAdded,
            // Refused, not supported on this Android version, or failed: the manual way.
            TileAddResult.NotAdded to TileNotice.ShowSteps,
        )
        for ((result, shown) in expected) {
            adder.result = result
            viewModel.onAddTileClick()
            assertEquals(shown, viewModel.tileNotice.value)
            viewModel.onTileNoticeDismiss()
            assertNull(viewModel.tileNotice.value)
        }
        assertEquals(3, adder.requests)
    }

    // --- the screen ---------------------------------------------------------------------

    @Test
    fun `the row is a button of at least 48 dp, says what the tile does, and reports the tap`() {
        compose.setContent { Settings() }

        compose.onNodeWithText(string(R.string.settings_shortcuts_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_tile_add_title))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinTouchTarget()
            .performClick()
        assertEquals(listOf("add"), events)

        // The honest part: it offers the dialer, it does not call, SafeRoute is no emergency service.
        val help = string(R.string.settings_tile_help)
        compose.onNodeWithText(help).performScrollTo().assertIsDisplayed()
        assert(help.contains("112") && help.contains(string(R.string.not_emergency_service)))
        // No dialog was shown by the screen itself.
        compose.onAllNodesWithText(string(R.string.settings_tile_ok)).assertCountEquals(0)
    }

    @Test
    fun `added and already added say so, and OK closes the notice`() {
        compose.setContent { Settings() }

        notice = TileNotice.Added
        compose.onNodeWithText(string(R.string.settings_tile_added_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_tile_added_body)).assertIsDisplayed()

        notice = TileNotice.AlreadyAdded
        compose.onNodeWithText(string(R.string.settings_tile_already_title)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.settings_tile_ok)).performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun `when Android does not add it, the four steps for doing it by hand are shown`() {
        notice = TileNotice.ShowSteps
        compose.setContent { Settings() }

        compose.onNodeWithText(string(R.string.settings_tile_steps_title)).assertIsDisplayed()
        for (step in listOf(
            R.string.settings_tile_steps_1,
            R.string.settings_tile_steps_2,
            R.string.settings_tile_steps_3,
            R.string.settings_tile_steps_4,
            R.string.settings_tile_steps_note,
        )) {
            compose.onNodeWithText(string(step)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(string(R.string.settings_tile_ok)).assertMinTouchTarget()
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali at double font size the row and the steps are still reachable`() {
        notice = TileNotice.ShowSteps
        compose.setContent { Settings(fontScale = 2f) }

        compose.onNodeWithText("নিজে টাইল যোগ করুন").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_tile_steps_4)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_tile_ok)).assertIsDisplayed().performClick()

        notice = null
        compose.onNodeWithText("SOS টাইল যোগ করুন").performScrollTo().assertIsDisplayed().assertMinTouchTarget()
    }
}
