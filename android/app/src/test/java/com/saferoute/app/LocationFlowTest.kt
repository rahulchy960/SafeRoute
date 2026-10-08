// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.location.FAKE_POSITION
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The location permission flow in the real [MainActivity]: Home route, both ViewModels, the
 * permission launcher and the lifecycle effects, with a fake phone underneath. Robolectric
 * records the permission request instead of showing Android's dialog; the test then answers it
 * the way Android would.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class LocationFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject lateinit var environment: FakeLocationEnvironment

    @Inject lateinit var location: FakeLocationRepository

    @Inject lateinit var mapEngine: FakeMapEngine

    @Before
    fun inject() = hilt.inject()

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun myLocation(description: Int = R.string.my_location_off) =
        compose.onNodeWithContentDescription(string(description))

    private fun requestedPermissions(): List<String>? =
        shadowOf(compose.activity).lastRequestedPermission?.requestedPermissions?.toList()

    /** Answers the recorded request as Android does after the user chose in its dialog. */
    private fun answerSystemDialog(granted: GrantedLocation) {
        environment.granted = granted
        val request = shadowOf(compose.activity).lastRequestedPermission
        val results = request.requestedPermissions.map { permission ->
            val allowed = when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION -> granted == GrantedLocation.Precise
                else -> granted != GrantedLocation.None
            }
            if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        compose.activityRule.scenario.onActivity {
            it.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, results.toIntArray())
            // Android clears this flag itself before it delivers a real answer. Calling the
            // callback directly skips that, and the next request would be dropped.
            ReflectionHelpers.setField(it, "mHasCurrentPermissionsRequest", false)
        }
        compose.waitForIdle()
    }

    private fun background() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
    }

    private fun foreground() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun grantThroughTheFlow(granted: GrantedLocation = GrantedLocation.Precise) {
        myLocation().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_continue)).performClick()
        compose.waitForIdle()
        answerSystemDialog(granted)
    }

    @Test
    fun `opening the app asks for nothing and starts nothing`() {
        compose.waitForIdle()

        assertNull(requestedPermissions())
        assertEquals(0, location.starts)
        compose.onNodeWithText(string(R.string.location_disclosure_title)).assertDoesNotExist()
        myLocation().assertIsDisplayed()
    }

    @Test
    fun `tap shows the explanation, and only continue reaches the system dialog`() {
        myLocation().performClick()

        compose.onNodeWithText(string(R.string.location_disclosure_title)).assertIsDisplayed()
        assertNull(requestedPermissions())

        compose.onNodeWithText(string(R.string.location_disclosure_continue)).performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            requestedPermissions(),
        )
        assertEquals(0, location.starts)
    }

    @Test
    fun `not now asks for nothing and leaves the map and the emergency button usable`() {
        myLocation().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_not_now)).performClick()
        compose.waitForIdle()

        assertNull(requestedPermissions())
        assertEquals(0, location.starts)
        compose.onNodeWithText(string(R.string.location_disclosure_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed()
        myLocation().assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        assertEquals(Intent.ACTION_DIAL, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.action)
    }

    @Test
    fun `allowing starts location, and the first fix draws the dot and centres the map once`() {
        grantThroughTheFlow()

        assertTrue(location.running)
        myLocation(R.string.my_location_searching).assertIsDisplayed()

        location.state.value = LocationState.Fix(fakeFix())
        compose.waitForIdle()

        myLocation(R.string.my_location_located).assertIsDisplayed()
        assertEquals(2, mapEngine.controller.overlays.size)
        assertEquals(FAKE_POSITION, mapEngine.controller.cameraMoves.single().target)

        // Second tap: follow. The icon's description changes.
        myLocation(R.string.my_location_located).performClick()
        myLocation(R.string.my_location_following).assertIsDisplayed()
    }

    @Test
    fun `updates stop when the app goes to the background and resume when it returns`() {
        grantThroughTheFlow()
        assertTrue(location.running)

        background()
        assertFalse(location.running)

        foreground()
        assertTrue(location.running)
    }

    @Test
    fun `approximate only shows the hint, and use precise asks once more`() {
        grantThroughTheFlow(GrantedLocation.Approximate)

        assertTrue(location.running)
        compose.onNodeWithText(string(R.string.location_notice_approximate_title)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.location_notice_use_precise)).performClick()
        compose.waitForIdle()
        answerSystemDialog(GrantedLocation.Precise)

        compose.onNodeWithText(string(R.string.location_notice_approximate_title)).assertDoesNotExist()
        assertTrue(location.running)
        // Restarted, so that the source is asked for precise updates.
        assertEquals(2, location.starts)
    }

    @Test
    fun `denying keeps the button, and the settings path opens the app's settings page`() {
        myLocation().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_continue)).performClick()
        compose.waitForIdle()
        // Robolectric answers at once and reports "no rationale", which is what Android does
        // when it no longer shows its dialog: denied for good.
        answerSystemDialog(GrantedLocation.None)

        assertEquals(0, location.starts)
        compose.onNodeWithText(string(R.string.location_notice_settings_body)).assertIsDisplayed()
        myLocation().assertIsDisplayed()

        compose.onNodeWithText(string(R.string.location_notice_open_settings)).performClick()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:com.saferoute.app", intent.dataString)

        // Another tap does not ask Android again: it repeats the way to Settings.
        compose.onNodeWithText(string(R.string.location_notice_close)).performClick()
        myLocation().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.location_notice_settings_body)).assertIsDisplayed()
    }

    @Test
    fun `location switched off offers the location settings and recovers on return`() {
        environment.granted = GrantedLocation.Precise
        environment.locationEnabled = false
        background()
        foreground()

        assertFalse(location.running)
        myLocation().performClick()
        compose.onNodeWithText(string(R.string.location_notice_off_title)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.location_notice_turn_on)).performClick()
        assertEquals(
            Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.action,
        )

        // The user switches Location on in Settings and comes back.
        environment.locationEnabled = true
        background()
        foreground()

        assertTrue(location.running)
        compose.onNodeWithText(string(R.string.location_notice_off_title)).assertDoesNotExist()
    }

    @Test
    fun `revoking the permission in settings stops location and removes the dot on return`() {
        grantThroughTheFlow()
        location.state.value = LocationState.Fix(fakeFix())
        compose.waitForIdle()
        assertEquals(2, mapEngine.controller.overlays.size)

        background()
        environment.granted = GrantedLocation.None
        foreground()

        assertFalse(location.running)
        assertTrue(mapEngine.controller.overlays.isEmpty())
        myLocation().assertIsDisplayed()
    }

    @Test
    fun `rotating the screen does not ask again`() {
        myLocation().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_continue)).performClick()
        compose.waitForIdle()
        assertTrue(requestedPermissions() != null)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        // The new activity has had no request made on it.
        assertNull(requestedPermissions())
    }
}
