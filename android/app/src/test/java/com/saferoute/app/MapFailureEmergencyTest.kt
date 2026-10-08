// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.MapStyleVariant
import com.saferoute.app.core.map.RegionDefaults
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The safety property of P010a: whatever the map does, the emergency button still leads to the
 * dialer with 112. The real [MainActivity], Home route and ViewModel, with the fake map engine
 * put into each state in turn. (SOS failure matrix, Plan v7 §7.5: no SOS logic changes here;
 * this is the one related check.)
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class MapFailureEmergencyTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject
    lateinit var mapEngine: FakeMapEngine

    @Before
    fun inject() = hilt.inject()

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun nextStartedActivity(): Intent? =
        shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    @Test
    fun `the emergency button reaches the dialer in every map state`() {
        val states = listOf(
            MapLoadState.Loading,
            MapLoadState.Error,
            MapLoadState.Offline,
            MapLoadState.RateLimited,
            MapLoadState.NotConfigured,
            MapLoadState.Ready,
        )
        states.forEach { state ->
            mapEngine.controller.loadState.value = state
            compose.waitForIdle()

            compose.onNodeWithText(string(R.string.emergency_button_label)).assertIsDisplayed().performClick()
            compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
            compose.waitForIdle()

            val intent = nextStartedActivity()
            assertEquals("$state", Intent.ACTION_DIAL, intent?.action)
            assertEquals("$state", "tel:112", intent?.dataString)
            assertNull(nextStartedActivity())
        }
    }

    @Test
    fun `home opens the map at the launch region, in the theme's style, with padding`() {
        compose.waitForIdle()

        compose.onNodeWithTag(FakeMapEngine.MAP_TAG).assertExists()
        // Nothing was saved, so the engine was asked for its default.
        assertEquals(listOf(null), mapEngine.initialCameras)
        assertEquals(RegionDefaults.overview, mapEngine.controller.camera.value)
        assertEquals(listOf(MapStyleVariant.Light), mapEngine.controller.variants)
        assertTrue(mapEngine.controller.paddings.last().bottom > 0)
    }

    @Test
    fun `retry on the status card reaches the map`() {
        mapEngine.controller.loadState.value = MapLoadState.Error
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.map_status_retry)).performClick()

        assertEquals(1, mapEngine.controller.retries)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-night")
    fun `dark mode asks for the dark style`() {
        compose.waitForIdle()
        assertEquals(listOf(MapStyleVariant.Dark), mapEngine.controller.variants)
    }

    @Test
    fun `rotating the phone keeps the map state and makes a new map view`() {
        val controller = mapEngine.controller
        val before = mapEngine.mapsComposed

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        // Same ViewModel, so the same controller; the view was composed again.
        assertEquals(1, mapEngine.controllers.size)
        assertEquals(controller, mapEngine.controller)
        assertTrue(mapEngine.mapsComposed > before)
    }
}
