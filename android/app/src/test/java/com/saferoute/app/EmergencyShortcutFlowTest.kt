// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.emergency.FakeEmergencyNotifier
import com.saferoute.app.feature.emergency.FakeEmergencyShortcutPreferences
import com.saferoute.app.feature.emergency.FakeNotificationGate
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification shortcut in the real app: nothing at launch, the one-time offer after the
 * SOS control was used, and the switch in Settings down to "posted". The notification, the
 * stored flags and Android's answers are fakes.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class EmergencyShortcutFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject lateinit var preferences: FakeEmergencyShortcutPreferences

    @Inject lateinit var gate: FakeNotificationGate

    @Inject lateinit var notifier: FakeEmergencyNotifier

    @Before
    fun setUp() {
        hilt.inject()
        // A phone on which the offer has not been shown yet.
        preferences.offerShown.value = false
    }

    private fun string(id: Int): String = compose.activity.getString(id)
    private fun sos() = compose.onNodeWithContentDescription(string(R.string.sos_control_description))
    private fun offer() = compose.onAllNodesWithText(string(R.string.shortcut_offer_title))

    @Test
    fun `launching the app offers nothing, requests nothing and posts nothing`() {
        compose.waitForIdle()

        offer().assertCountEquals(0)
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        assertFalse("post" in notifier.events)
        assertFalse(preferences.offerShown.value)
    }

    @Test
    fun `after the first use of the SOS control the offer appears once, and leads to Settings`() {
        sos().performClick()
        // While the emergency dialog is open, nothing else is put on top of it.
        offer().assertCountEquals(0)
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()

        compose.onNodeWithText(string(R.string.shortcut_offer_title)).assertIsDisplayed()
        // The offer itself asks Android for nothing.
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        compose.onNodeWithText(string(R.string.shortcut_offer_set_up)).performClick()
        compose.onNodeWithText(string(R.string.settings_notification_title)).performScrollTo().assertIsDisplayed()

        // Back on Home, using SOS again: no second offer.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        sos().performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()
        offer().assertCountEquals(0)
    }

    @Test
    fun `Not now closes the offer for good`() {
        sos().performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()
        compose.onNodeWithText(string(R.string.shortcut_offer_not_now)).performClick()

        offer().assertCountEquals(0)
        assertTrue(preferences.offerShown.value)
        sos().assertIsDisplayed()
    }

    @Test
    fun `in Settings, the switch explains, then turns the shortcut on and posts it`() {
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        val row = compose.onNodeWithText(string(R.string.settings_notification_title)).performScrollTo()

        row.performClick()
        compose.onNodeWithText(string(R.string.settings_notification_explain_what)).assertIsDisplayed()
        assertFalse(preferences.notificationEnabled.value)

        // Android allows notifications already (the fake's default): no system dialog.
        compose.onNodeWithText(string(R.string.settings_notification_continue)).performClick()
        compose.waitForIdle()

        assertTrue(preferences.notificationEnabled.value)
        assertTrue(notifier.shown)
        row.assertIsOn()

        // Off again removes it.
        row.performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(preferences.notificationEnabled.value)
        assertEquals("cancel", notifier.events.last())
    }

    @Test
    fun `when Android has not allowed notifications, Continue asks for the permission once`() {
        gate.block = com.saferoute.app.feature.emergency.NotificationBlock.PermissionMissing
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.settings_notification_title)).performScrollTo().performClick()
        // Nothing was requested by opening Settings or by tapping the switch.
        assertNull(shadowOf(compose.activity).lastRequestedPermission)

        compose.onNodeWithText(string(R.string.settings_notification_continue)).performClick()
        compose.waitForIdle()

        val requested = shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), requested?.requestedPermissions?.toList())
        assertFalse(preferences.notificationEnabled.value)
    }
}
