// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.SafeRouteSheetDefaults
import com.saferoute.app.core.designsystem.component.SafeRouteSheetState
import com.saferoute.app.core.designsystem.component.SheetDetent
import com.saferoute.app.core.designsystem.component.rememberSafeRouteSheetState
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.MapPadding
import com.saferoute.app.core.map.MapProviderConfig
import com.saferoute.app.testing.assertMinTouchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val paddings = mutableListOf<MapPadding>()
    private lateinit var sheet: SafeRouteSheetState
    private lateinit var scope: CoroutineScope

    /** Every state in which the map is not simply showing. */
    private val troubledMapStates = listOf(
        MapLoadState.Loading,
        MapLoadState.Error,
        MapLoadState.Offline,
        MapLoadState.RateLimited,
        MapLoadState.NotConfigured,
    )

    private fun string(id: Int): String = context.getString(id)

    private companion object {
        const val MapTag = "map"
    }

    @Composable
    private fun Home(
        dialog: EmergencyDialogState = EmergencyDialogState.Hidden,
        mapState: MapLoadState = MapLoadState.Ready,
        fontScale: Float = 1f,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                sheet = rememberSafeRouteSheetState()
                scope = rememberCoroutineScope()
                HomeScreen(
                    emergencyDialog = dialog,
                    onSearchClick = { events += "search" },
                    onSettingsClick = { events += "settings" },
                    onEmergencyClick = { events += "emergency" },
                    onCallEmergency = { events += "call" },
                    onDismissEmergencyDialog = { events += "dismiss" },
                    mapState = mapState,
                    onMapRetry = { events += "retry" },
                    onMapPaddingChange = { paddings += it },
                    onOpenLink = { events += "link:$it" },
                    sheetState = sheet,
                    // The fake map: a tagged box where the real map would be.
                    map = { mapModifier -> Box(mapModifier.testTag(MapTag)) },
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
    fun `a ready map shows the map and its credit and no status card`() {
        compose.setContent { Home() }

        compose.onNodeWithTag(MapTag).assertExists()
        compose.onNodeWithContentDescription(string(R.string.map_content_description)).assertExists()
        compose.onNodeWithText(string(R.string.map_attribution)).assertIsDisplayed().assertMinTouchTarget()
        compose.onNodeWithText(string(R.string.map_status_rest_works)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.map_status_retry)).assertDoesNotExist()
    }

    @Test
    fun `each map problem has its own message`() {
        val titles = mapOf(
            MapLoadState.Loading to R.string.map_status_loading,
            MapLoadState.Error to R.string.map_status_error_title,
            MapLoadState.Offline to R.string.map_status_offline_title,
            MapLoadState.RateLimited to R.string.map_status_rate_limited_title,
            MapLoadState.NotConfigured to R.string.map_status_not_configured_title,
        )
        assertEquals(troubledMapStates.size, titles.values.map(::string).toSet().size)

        var state by mutableStateOf<MapLoadState>(MapLoadState.Ready)
        compose.setContent { Home(mapState = state) }
        titles.forEach { (mapState, title) ->
            state = mapState
            compose.onNodeWithText(string(title)).assertIsDisplayed()
        }
    }

    @Test
    fun `a failed map offers a retry, a missing key and loading do not`() {
        var state by mutableStateOf<MapLoadState>(MapLoadState.Error)
        compose.setContent { Home(mapState = state) }

        listOf(MapLoadState.Error, MapLoadState.Offline, MapLoadState.RateLimited).forEach {
            state = it
            compose.onNodeWithText(string(R.string.map_status_retry)).assertMinTouchTarget().performClick()
        }
        assertEquals(listOf("retry", "retry", "retry"), events)

        listOf(MapLoadState.NotConfigured, MapLoadState.Loading).forEach {
            state = it
            compose.onNodeWithText(string(R.string.map_status_retry)).assertDoesNotExist()
        }
    }

    @Test
    fun `no map problem ever exposes technical details`() {
        var state by mutableStateOf<MapLoadState>(MapLoadState.RateLimited)
        compose.setContent { Home(mapState = state) }

        troubledMapStates.forEach {
            state = it
            listOf("403", "429", "HTTP", "key=", "maptiler.com").forEach { detail ->
                compose.onNodeWithText(detail, substring = true).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `in every map state the emergency button opens the dialog and the dialog can call`() {
        var state by mutableStateOf<MapLoadState>(MapLoadState.Ready)
        var dialog by mutableStateOf(EmergencyDialogState.Hidden)
        compose.setContent { Home(dialog = dialog, mapState = state) }

        (troubledMapStates + MapLoadState.Ready).forEach { mapState ->
            state = mapState
            dialog = EmergencyDialogState.Hidden
            events.clear()

            emergencyButton().assertIsDisplayed().assertMinTouchTarget().performClick()
            assertEquals("$mapState", listOf("emergency"), events)

            dialog = EmergencyDialogState.OfferDialer
            compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
            assertEquals("$mapState", listOf("emergency", "call"), events)
        }
    }

    @Test
    fun `in every map state search, settings and the sheet keep working`() {
        var state by mutableStateOf<MapLoadState>(MapLoadState.Ready)
        compose.setContent { Home(mapState = state) }

        troubledMapStates.forEach { mapState ->
            state = mapState
            events.clear()

            searchPill().assertIsDisplayed().performClick()
            settingsButton().assertIsDisplayed().performClick()
            assertEquals("$mapState", listOf("search", "settings"), events)

            myLocation().assertIsDisplayed()
            layers().assertIsDisplayed()
            val before = sheet.currentDetent
            sheetHandle().assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertTrue("$mapState", sheet.currentDetent != before)
            // Back down: a fully open sheet covers the search pill, as it always has.
            scope.launch { sheet.animateTo(SheetDetent.Peek) }
            compose.waitForIdle()
        }
    }

    @Test
    fun `without a map key no map is composed and there is nothing to credit`() {
        compose.setContent { Home(mapState = MapLoadState.NotConfigured) }

        compose.onNodeWithTag(MapTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.map_attribution)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.map_status_not_configured_body)).assertIsDisplayed()
    }

    @Test
    fun `the credit opens a dialog with both licence links`() {
        compose.setContent { Home() }

        compose.onNodeWithText(string(R.string.map_attribution)).performClick()
        compose.onNodeWithText(string(R.string.map_attribution_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.map_attribution_provider_link)).performClick()
        compose.onNodeWithText(string(R.string.map_attribution_data_link)).performClick()
        compose.onNodeWithText(string(R.string.map_attribution_close)).performClick()

        compose.onNodeWithText(string(R.string.map_attribution_dialog_title)).assertDoesNotExist()
        assertEquals(
            listOf(
                "link:${MapProviderConfig.PROVIDER_COPYRIGHT_URL}",
                "link:${MapProviderConfig.DATA_COPYRIGHT_URL}",
            ),
            events,
        )
    }

    @Test
    fun `the credit stays clear of the sheet at peek and half`() {
        compose.setContent { Home() }
        val credit = compose.onNodeWithText(string(R.string.map_attribution))

        listOf(SheetDetent.Peek, SheetDetent.Half).forEach { detent ->
            scope.launch { sheet.animateTo(detent) }
            compose.waitForIdle()
            val creditBottom = credit.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom
            val sheetTop = sheetHandle().fetchSemanticsNode().boundsInRoot.top
            assertTrue("$detent: credit $creditBottom, sheet $sheetTop", creditBottom <= sheetTop)
        }
    }

    @Test
    fun `the map padding follows the sheet and the search pill`() {
        compose.setContent { Home() }
        compose.waitForIdle()

        val density = compose.density.density
        val heightPx = (640 * density).toInt()
        val peekPx = (SafeRouteSheetDefaults.PeekHeight.value * density).toInt()
        val atPeek = paddings.last()
        assertEquals(peekPx, atPeek.bottom)
        // The top padding ends where the credit under the search pill ends.
        val creditBottom = compose.onNodeWithText(string(R.string.map_attribution))
            .fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(creditBottom, atPeek.top.toFloat(), 1f)

        scope.launch { sheet.animateTo(SheetDetent.Half) }
        compose.waitForIdle()
        assertEquals(heightPx / 2, paddings.last().bottom)
        assertEquals(atPeek.top, paddings.last().top)

        // Fully open the map is hidden: the padding stays where it was at half.
        scope.launch { sheet.animateTo(SheetDetent.Full) }
        compose.waitForIdle()
        assertEquals(heightPx / 2, paddings.last().bottom)
    }

    @Test
    fun `the bottom padding rule`() {
        assertEquals(148 + 24, mapBottomPaddingPx(SheetDetent.Peek, 1000, peekHeightPx = 148, navigationBarPx = 24))
        assertEquals(500, mapBottomPaddingPx(SheetDetent.Half, 1000, peekHeightPx = 148, navigationBarPx = 24))
        assertEquals(500, mapBottomPaddingPx(SheetDetent.Full, 1000, peekHeightPx = 148, navigationBarPx = 24))
        // A very short window: never more than there is.
        assertEquals(100, mapBottomPaddingPx(SheetDetent.Peek, 100, peekHeightPx = 148, navigationBarPx = 24))
    }

    @Test
    fun `at 200 percent font a map problem still leaves the key controls shown and big enough`() {
        compose.setContent { Home(mapState = MapLoadState.Offline, fontScale = 2f) }

        compose.onNodeWithText(string(R.string.map_status_offline_title)).assertIsDisplayed()
        listOf(searchPill(), settingsButton(), emergencyButton(), sheetHandle())
            .forEach { it.assertIsDisplayed().assertMinTouchTarget() }
        emergencyButton().performClick()
        assertEquals(listOf("emergency"), events)
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
    fun `the map messages are in bengali when the language is bengali`() {
        compose.setContent { Home(mapState = MapLoadState.Offline) }

        compose.onNodeWithText("অফলাইনে মানচিত্র পাওয়া যাচ্ছে না").assertIsDisplayed()
        compose.onNodeWithText("আবার চেষ্টা করুন").assertIsDisplayed()
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
