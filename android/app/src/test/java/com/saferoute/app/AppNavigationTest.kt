// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole app in the real [MainActivity]: navigation and its back stack, and the emergency
 * button down to the intent handed to Android. Robolectric records started activities instead
 * of opening them, so the test can read the intent.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class AppNavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    // Signed in and ready: the app's own screens are shown. The fake replaces the real session,
    // so the test needs no Firebase and never calls the server this machine's build points at.
    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun assertOnHome() {
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.settings_about_title)).assertDoesNotExist()
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun nextStartedActivity(): Intent? =
        shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    @Test
    fun `starts on home`() {
        assertOnHome()
    }

    @Test
    fun `home to search and system back returns to home`() {
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()
        // The search screen has the SOS control too, as an action in its top bar.
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()

        pressSystemBack()
        assertOnHome()
    }

    @Test
    fun `on the search screen SOS opens the same dialog, and call 112 opens the dialer and never calls`() {
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service), substring = true).assertExists()
        assertNull(nextStartedActivity())

        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.waitForIdle()

        val intent = nextStartedActivity()
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertNotEquals(Intent.ACTION_CALL, intent?.action)
        assertEquals("tel:112", intent?.dataString)
        assertNull(nextStartedActivity())
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
        // Still on the search screen: the dialog did not navigate anywhere.
        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `on the search screen, cancel and a missing dialer behave as on Home`() {
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
        assertNull(nextStartedActivity())

        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_no_dialer)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.emergency_dialog_close)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun `home to search and the back arrow returns to home`() {
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.navigate_back)).performClick()
        assertOnHome()
    }

    @Test
    fun `home to settings shows the real version and back returns to home`() {
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        // Further down the list since the shortcuts section was added: it exists, it may need a scroll.
        compose.onNodeWithText(string(R.string.settings_about_title)).assertExists()
        compose.onNodeWithText(
            compose.activity.getString(
                R.string.settings_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        ).performScrollTo().assertIsDisplayed()

        pressSystemBack()
        assertOnHome()
    }

    @Test
    fun `back on home leaves the app`() {
        pressSystemBack()
        assertEquals(true, compose.activity.isFinishing)
    }

    @Test
    fun `emergency button, then call 112, opens the dialer and never calls`() {
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertIsDisplayed()
        // Nothing may be started by the button itself.
        assertNull(nextStartedActivity())

        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.waitForIdle()

        val intent = nextStartedActivity()
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertNotEquals(Intent.ACTION_CALL, intent?.action)
        assertEquals("tel:112", intent?.dataString)
        // Exactly one activity was started.
        assertNull(nextStartedActivity())
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun `cancel closes the dialog without starting anything`() {
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()

        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
        assertNull(nextStartedActivity())
        assertOnHome()
    }

    @Test
    fun `without a dialer app the dialog shows the number instead of crashing`() {
        // Makes Robolectric behave like a device with no app for the intent: startActivity
        // throws ActivityNotFoundException.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)

        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()

        compose.onNodeWithText(string(R.string.emergency_dialog_no_dialer)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.emergency_dialog_close)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertDoesNotExist()
    }
}
