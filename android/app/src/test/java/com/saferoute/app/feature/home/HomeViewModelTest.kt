// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The emergency dialog's state machine and the saved map camera. Plain JVM test: a ViewModel
 * is an ordinary class.
 *
 * `viewModelScope` runs on the main thread, which a JVM test does not have, so `setMain` puts a
 * test dispatcher in its place.
 *
 * Turbine's `test { }` collects the StateFlow and `awaitItem()` returns each value in the order
 * it was emitted, so the test reads like the sequence of events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val savedState = SavedStateHandle()
    private val mapEngine = FakeMapEngine()
    private val location = FakeLocationRepository()
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = HomeViewModel(savedState, mapEngine, location, MapSelection(), RouteDisplay())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a first start has no saved camera and asks the map for its default`() {
        assertEquals(listOf<CameraState?>(null), mapEngine.initialCameras)
    }

    @Test
    fun `the camera is saved when the map comes to rest and restored after process death`() {
        val moved = CameraState(LatLng(10.5, 20.25), zoom = 15.5, bearing = 30.0)
        mapEngine.controller.camera.value = moved

        // Android hands the saved values to a new ViewModel in a new process.
        val restoredEngine = FakeMapEngine()
        HomeViewModel(savedState, restoredEngine, location, MapSelection(), RouteDisplay())

        assertEquals(listOf<CameraState?>(moved), restoredEngine.initialCameras)
    }

    @Test
    fun `a damaged saved camera is ignored`() {
        val engine = FakeMapEngine()
        HomeViewModel(SavedStateHandle(mapOf("map_camera" to doubleArrayOf(1.0, 2.0))), engine, location, MapSelection(), RouteDisplay())

        assertEquals(listOf<CameraState?>(null), engine.initialCameras)
    }

    @Test
    fun `the map failing does not touch the emergency dialog`() {
        viewModel.onEmergencyClick()
        mapEngine.controller.loadState.value = MapLoadState.Error

        assertEquals(EmergencyDialogState.OfferDialer, viewModel.emergencyDialog.value)
    }

    @Test
    fun `starts with no dialog`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `emergency tap offers the dialer and cancel hides it`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            viewModel.onEmergencyDialogDismiss()
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
        }
    }

    @Test
    fun `opening the dialer closes the dialog`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            viewModel.onDialerOpened()
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
        }
    }

    @Test
    fun `a missing dialer keeps the dialog open until it is closed`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            viewModel.onDialerUnavailable()
            assertEquals(EmergencyDialogState.DialerUnavailable, awaitItem())

            viewModel.onEmergencyDialogDismiss()
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
        }
    }

    @Test
    fun `a second emergency tap does not emit again`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            // A StateFlow only reports changes; the same value twice is one dialog.
            viewModel.onEmergencyClick()
            expectNoEvents()
        }
    }
}
