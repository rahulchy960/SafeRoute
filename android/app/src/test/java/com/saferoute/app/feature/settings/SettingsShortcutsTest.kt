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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import com.saferoute.app.feature.emergency.EmergencyNotificationController
import com.saferoute.app.feature.emergency.FakeEmergencyNotifier
import com.saferoute.app.feature.emergency.FakeEmergencyShortcutPreferences
import com.saferoute.app.feature.emergency.FakeNotificationGate
import com.saferoute.app.feature.emergency.FakeTileAdder
import com.saferoute.app.feature.emergency.NotificationBlock
import com.saferoute.app.feature.emergency.TileAddResult
import com.saferoute.app.testing.assertMinTouchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Add the SOS tile" in Settings: the request, its three answers, and the manual way. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SettingsShortcutsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)

    private val events = mutableListOf<String>()
    private var notice: TileNotice? by mutableStateOf(null)
    private var notification by mutableStateOf(NotificationShortcutUiState())

    /** The view model with fakes for everything it talks to. */
    private class Parts(enabled: Boolean = false, block: NotificationBlock = NotificationBlock.None) {
        val preferences = FakeEmergencyShortcutPreferences(enabled = enabled)
        val gate = FakeNotificationGate(block)
        val notifier = FakeEmergencyNotifier()
        private val scope = CoroutineScope(UnconfinedTestDispatcher())
        val controller = EmergencyNotificationController(preferences, gate, notifier, scope)

        fun viewModel(adder: FakeTileAdder = FakeTileAdder()) =
            EmergencyShortcutsViewModel(adder, preferences, gate, controller)
    }

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

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
                    notification = notification,
                    notificationActions = NotificationShortcutActions(
                        onToggle = { events += "toggle:$it" },
                        onExplanationContinue = { events += "continue" },
                        onExplanationDismiss = { events += "not-now" },
                        onNoticeDismiss = { events += "notice-close" },
                        onOpenSystemSettings = { events += "system-settings" },
                    ),
                )
            }
        }
    }

    // --- the view model -----------------------------------------------------------------

    @Test
    fun `nothing is requested until the row is tapped, and each answer has its notice`() {
        val adder = FakeTileAdder()
        val viewModel = Parts().viewModel(adder)
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

    // --- the notification's permission state machine --------------------------------------

    @Test
    fun `opening Settings requests nothing and posts nothing`() {
        val parts = Parts(block = NotificationBlock.PermissionMissing)
        val viewModel = parts.viewModel()
        viewModel.refresh()

        val state = viewModel.notification.value
        assertEquals(NotificationShortcutUiState(), state)
        assertFalse(state.requestPending)
        assertFalse("post" in parts.notifier.events)
    }

    @Test
    fun `turning it on explains first - Not now changes nothing, Continue asks Android once`() {
        val parts = Parts(block = NotificationBlock.PermissionMissing)
        val viewModel = parts.viewModel()

        viewModel.onNotificationToggle(true)
        assertTrue(viewModel.notification.value.explaining)
        assertFalse(viewModel.notification.value.requestPending)

        viewModel.onExplanationDismiss()
        assertEquals(NotificationShortcutUiState(), viewModel.notification.value)
        assertFalse(parts.preferences.notificationEnabled.value)

        viewModel.onNotificationToggle(true)
        viewModel.onExplanationContinue()
        assertTrue(viewModel.notification.value.requestPending)
        // A second "Continue" (a double tap) asks nothing more.
        viewModel.onRequestLaunched()
        viewModel.onExplanationContinue()
        assertFalse(viewModel.notification.value.requestPending)
        assertFalse(parts.preferences.notificationEnabled.value)
    }

    @Test
    fun `granted - the switch is stored and the notification is posted`() {
        val parts = Parts(block = NotificationBlock.PermissionMissing)
        val viewModel = parts.viewModel()
        viewModel.onNotificationToggle(true)
        viewModel.onExplanationContinue()
        viewModel.onRequestLaunched()

        parts.gate.block = NotificationBlock.None
        viewModel.onPermissionResult(showRationale = false)

        assertTrue(parts.preferences.notificationEnabled.value)
        assertTrue(parts.notifier.shown)
        assertEquals(
            NotificationShortcutUiState(enabled = true),
            viewModel.notification.value,
        )
    }

    @Test
    fun `denied once and denied for good each have their notice, and the switch stays off`() {
        val parts = Parts(block = NotificationBlock.PermissionMissing)
        val viewModel = parts.viewModel()

        // Refused the first time: Android would still ask again.
        viewModel.onNotificationToggle(true)
        viewModel.onExplanationContinue()
        viewModel.onRequestLaunched()
        viewModel.onPermissionResult(showRationale = true)
        assertEquals(NotificationNotice.DeniedOnce, viewModel.notification.value.notice)

        // Trying again is one more tap, one more explanation, one more request. No loop.
        viewModel.onNotificationNoticeDismiss()
        assertFalse(viewModel.notification.value.requestPending)
        viewModel.onNotificationToggle(true)
        viewModel.onExplanationContinue()
        viewModel.onRequestLaunched()
        viewModel.onPermissionResult(showRationale = false)
        assertEquals(NotificationNotice.DeniedForGood, viewModel.notification.value.notice)

        assertFalse(parts.preferences.notificationEnabled.value)
        assertFalse("post" in parts.notifier.events)
    }

    @Test
    fun `notifications or the category blocked - a notice, no request, the switch stays off`() {
        for ((block, notice) in listOf(
            NotificationBlock.AppBlocked to NotificationNotice.AppBlocked,
            NotificationBlock.ChannelBlocked to NotificationNotice.ChannelBlocked,
        )) {
            val parts = Parts(block = block)
            val viewModel = parts.viewModel()

            viewModel.onNotificationToggle(true)
            viewModel.onExplanationContinue()

            assertEquals(notice, viewModel.notification.value.notice)
            assertFalse(viewModel.notification.value.requestPending)
            assertFalse(parts.preferences.notificationEnabled.value)
        }
    }

    @Test
    fun `already allowed - Continue turns it on without any system dialog`() {
        val parts = Parts()
        val viewModel = parts.viewModel()

        viewModel.onNotificationToggle(true)
        viewModel.onExplanationContinue()

        assertFalse(viewModel.notification.value.requestPending)
        assertTrue(parts.preferences.notificationEnabled.value)
        assertTrue(parts.notifier.shown)
    }

    @Test
    fun `turning it off removes the notification and asks nothing`() {
        val parts = Parts(enabled = true)
        val viewModel = parts.viewModel()
        viewModel.refresh()
        assertTrue(parts.notifier.shown)

        viewModel.onNotificationToggle(false)

        assertFalse(parts.preferences.notificationEnabled.value)
        assertEquals("cancel", parts.notifier.events.last())
        assertFalse(viewModel.notification.value.explaining)
    }

    @Test
    fun `on, then blocked in system settings - the switch stays on and the screen says why`() {
        val parts = Parts(enabled = true)
        val viewModel = parts.viewModel()
        assertEquals(NotificationBlock.None, viewModel.notification.value.block)

        // The user comes back from system settings, where they switched the category off.
        parts.gate.block = NotificationBlock.ChannelBlocked
        viewModel.refresh()

        val state = viewModel.notification.value
        assertTrue(state.enabled)
        assertEquals(NotificationBlock.ChannelBlocked, state.block)
        assertEquals("cancel", parts.notifier.events.last())

        // Allowed again: back on the next time the screen (or the app) is opened.
        parts.gate.block = NotificationBlock.None
        viewModel.refresh()
        assertTrue(parts.notifier.shown)
    }

    // --- the notification switch on the screen ---------------------------------------------

    @Test
    fun `the row is a switch of at least 48 dp, says its state, and states the limits`() {
        compose.setContent { Settings() }

        val row = compose.onNodeWithText(string(R.string.settings_notification_title)).performScrollTo()
        row.assertIsDisplayed().assertIsOff().assertMinTouchTarget()
        compose.onNodeWithText(string(R.string.settings_notification_off)).assertExists()
        val limits = string(R.string.settings_notification_limits)
        compose.onNodeWithText(limits).performScrollTo().assertIsDisplayed()
        assert(limits.contains("swiped away") && limits.contains("tile"))

        row.performScrollTo().performClick()
        assertEquals(listOf("toggle:true"), events)

        notification = NotificationShortcutUiState(enabled = true)
        row.assertIsOn()
        compose.onNodeWithText(string(R.string.settings_notification_on)).assertExists()
        row.performScrollTo().performClick()
        assertEquals("toggle:false", events.last())
    }

    @Test
    fun `the explanation says what it does and offers Continue and Not now`() {
        notification = NotificationShortcutUiState(explaining = true)
        compose.setContent { Settings() }

        val what = string(R.string.settings_notification_explain_what)
        compose.onNodeWithText(what).assertIsDisplayed()
        assert(what.contains("112") && what.contains("Nothing is dialled"))
        compose.onNodeWithText(string(R.string.settings_notification_explain_permission)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(string(R.string.settings_notification_continue)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.settings_notification_not_now)).assertMinTouchTarget().performClick()
        assertEquals(listOf("continue", "not-now"), events)
    }

    @Test
    fun `each refusal has its words - system settings are offered except after a first refusal`() {
        compose.setContent { Settings() }
        val cases = listOf(
            Triple(NotificationNotice.DeniedOnce, R.string.settings_notification_denied_once, false),
            Triple(NotificationNotice.DeniedForGood, R.string.settings_notification_denied_for_good, true),
            Triple(NotificationNotice.AppBlocked, R.string.settings_notification_app_blocked, true),
            Triple(NotificationNotice.ChannelBlocked, R.string.settings_notification_channel_blocked, true),
        )
        for ((notice, text, offersSettings) in cases) {
            notification = NotificationShortcutUiState(notice = notice)
            compose.onNodeWithText(string(text)).assertIsDisplayed()
            compose.onAllNodesWithText(string(R.string.settings_notification_open_settings))
                .assertCountEquals(if (offersSettings) 1 else 0)
        }
        compose.onNodeWithText(string(R.string.settings_notification_open_settings)).performClick()
        compose.onNodeWithText(string(R.string.settings_notification_close)).performClick()
        assertEquals(listOf("system-settings", "notice-close"), events)
    }

    @Test
    fun `on but blocked - the row says so and offers system settings`() {
        notification = NotificationShortcutUiState(enabled = true, block = NotificationBlock.AppBlocked)
        compose.setContent { Settings() }

        compose.onNodeWithText(string(R.string.settings_notification_on_blocked)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_notification_open_settings))
            .performScrollTo()
            .assertMinTouchTarget()
            .performClick()
        assertEquals(listOf("system-settings"), events)
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali at double font size the switch and the explanation's buttons are reachable`() {
        notification = NotificationShortcutUiState(explaining = true)
        compose.setContent { Settings(fontScale = 2f) }

        compose.onNodeWithText("এগিয়ে যান").assertIsDisplayed().assertMinTouchTarget()
        compose.onNodeWithText("এখন নয়").assertIsDisplayed().performClick()

        notification = NotificationShortcutUiState()
        compose.onNodeWithText("বিজ্ঞপ্তিতে জরুরি শর্টকাট").performScrollTo().assertIsDisplayed().assertMinTouchTarget()
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
