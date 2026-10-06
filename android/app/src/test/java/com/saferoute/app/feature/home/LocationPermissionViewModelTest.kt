// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.GrantedLocation
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every path through the location permission. The fake environment plays Android: the test sets
 * what the user chose in the system dialog or in Settings, then tells the ViewModel that the
 * dialog was answered or that the screen resumed.
 */
class LocationPermissionViewModelTest {

    private val environment = FakeLocationEnvironment()
    private val savedState = SavedStateHandle()
    private var now = 10_000L
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private fun viewModel() = LocationPermissionViewModel(savedState, environment, clock)

    private val LocationPermissionViewModel.permission get() = state.value.permission
    private val LocationPermissionViewModel.notice get() = state.value.notice

    /** Tap, read the explanation, continue; the user then takes [seconds] to answer. */
    private fun LocationPermissionViewModel.askAndWait(seconds: Long = 3) {
        onMyLocationClick()
        onDisclosureContinue()
        assertTrue(state.value.requestPending)
        onRequestLaunched()
        now += seconds * 1_000
    }

    @Test
    fun `nothing is requested when the app starts or the screen resumes`() {
        val vm = viewModel()
        vm.refresh(showRationale = false)

        assertEquals(LocationPermissionState.NotAsked, vm.permission)
        assertFalse(vm.state.value.requestPending)
        assertNull(vm.notice)
    }

    @Test
    fun `a tap shows the explanation first and the system dialog only after continue`() {
        val vm = viewModel()

        assertFalse(vm.onMyLocationClick())
        assertEquals(LocationPermissionState.DisclosureShown, vm.permission)
        assertFalse(vm.state.value.requestPending)

        vm.onDisclosureContinue()
        assertTrue(vm.state.value.requestPending)
    }

    @Test
    fun `continue cannot fire a request without the explanation on screen`() {
        val vm = viewModel()

        vm.onDisclosureContinue()

        assertFalse(vm.state.value.requestPending)
    }

    @Test
    fun `not now returns to where it was, asks nothing and shows nothing`() {
        val vm = viewModel()
        vm.onMyLocationClick()

        vm.onDisclosureNotNow()

        assertEquals(LocationPermissionState.NotAsked, vm.permission)
        assertFalse(vm.state.value.requestPending)
        assertNull(vm.notice)
        // The button is still the way in.
        vm.onMyLocationClick()
        assertEquals(LocationPermissionState.DisclosureShown, vm.permission)
    }

    @Test
    fun `precise granted`() {
        val vm = viewModel()
        vm.askAndWait()
        environment.granted = GrantedLocation.Precise

        vm.onPermissionResult(showRationale = false)

        assertEquals(LocationPermissionState.Granted(precise = true), vm.permission)
        assertNull(vm.notice)
        assertTrue(vm.consumeUserAsked())
        assertFalse(vm.consumeUserAsked())
        assertTrue(vm.onMyLocationClick())
    }

    @Test
    fun `approximate only is used, with a gentle hint and one offer of precise`() {
        val vm = viewModel()
        vm.askAndWait()
        environment.granted = GrantedLocation.Approximate
        vm.onPermissionResult(showRationale = false)

        assertEquals(LocationPermissionState.Granted(precise = false), vm.permission)
        assertEquals(LocationNotice.Approximate, vm.notice)

        // "Use precise location": one more system dialog, straight away.
        vm.onUsePreciseClick()
        assertTrue(vm.state.value.requestPending)
        vm.onRequestLaunched()
        now += 2_000
        vm.onPermissionResult(showRationale = false)

        // Still approximate: the hint stays but no longer offers, and asking again does nothing.
        assertEquals(LocationPermissionState.Granted(precise = false), vm.permission)
        assertEquals(LocationNotice.ApproximateOnly, vm.notice)
        vm.onUsePreciseClick()
        assertFalse(vm.state.value.requestPending)
    }

    @Test
    fun `approximate upgraded to precise`() {
        val vm = viewModel()
        vm.askAndWait()
        environment.granted = GrantedLocation.Approximate
        vm.onPermissionResult(showRationale = false)

        vm.onUsePreciseClick()
        vm.onRequestLaunched()
        environment.granted = GrantedLocation.Precise
        vm.onPermissionResult(showRationale = false)

        assertEquals(LocationPermissionState.Granted(precise = true), vm.permission)
        assertNull(vm.notice)
    }

    @Test
    fun `denied once explains briefly and keeps the button working`() {
        val vm = viewModel()
        vm.askAndWait()

        vm.onPermissionResult(showRationale = true)

        assertEquals(LocationPermissionState.DeniedOnce, vm.permission)
        assertEquals(LocationNotice.DeniedOnce, vm.notice)
        assertFalse(vm.state.value.requestPending)

        vm.onMyLocationClick()
        assertEquals(LocationPermissionState.DisclosureShown, vm.permission)
    }

    @Test
    fun `denied twice is permanent and the button then points to settings without asking`() {
        val vm = viewModel()
        vm.askAndWait()
        vm.onPermissionResult(showRationale = true)

        vm.askAndWait()
        vm.onPermissionResult(showRationale = false)

        assertEquals(LocationPermissionState.DeniedPermanently, vm.permission)
        assertEquals(LocationNotice.DeniedPermanently, vm.notice)

        vm.onNoticeDismiss()
        assertFalse(vm.onMyLocationClick())
        assertEquals(LocationNotice.DeniedPermanently, vm.notice)
        assertFalse(vm.state.value.requestPending)
        assertEquals(LocationPermissionState.DeniedPermanently, vm.permission)
    }

    @Test
    fun `a dialog closed without an answer is not treated as permanent`() {
        val vm = viewModel()
        vm.askAndWait(seconds = 4)

        vm.onPermissionResult(showRationale = false)

        assertEquals(LocationPermissionState.DeniedOnce, vm.permission)
    }

    @Test
    fun `an instant refusal means android did not show its dialog - permanent`() {
        val vm = viewModel()
        vm.onMyLocationClick()
        vm.onDisclosureContinue()
        vm.onRequestLaunched()
        now += 50

        vm.onPermissionResult(showRationale = false)

        assertEquals(LocationPermissionState.DeniedPermanently, vm.permission)
    }

    @Test
    fun `a denial from an earlier visit asks for the explanation again`() {
        val vm = viewModel()

        vm.refresh(showRationale = true)

        assertEquals(LocationPermissionState.RationaleNeeded, vm.permission)
        assertNull(vm.notice)
        vm.onMyLocationClick()
        assertEquals(LocationPermissionState.DisclosureShown, vm.permission)
    }

    @Test
    fun `location switched off is its own state and recovers when switched on`() {
        environment.granted = GrantedLocation.Precise
        environment.locationEnabled = false
        val vm = viewModel()
        vm.refresh(showRationale = false)

        assertEquals(LocationPermissionState.ServicesOff, vm.permission)
        assertNull(vm.notice)
        assertFalse(vm.onMyLocationClick())
        assertEquals(LocationNotice.ServicesOff, vm.notice)

        // The user comes back from Settings without switching it on: nothing changes, no loop.
        vm.refresh(showRationale = false)
        assertEquals(LocationPermissionState.ServicesOff, vm.permission)
        assertFalse(vm.state.value.requestPending)

        environment.locationEnabled = true
        vm.refresh(showRationale = false)
        assertEquals(LocationPermissionState.Granted(precise = true), vm.permission)
        assertNull(vm.notice)
    }

    @Test
    fun `no play services is reported and nothing is requested`() {
        environment.playServices = false
        val vm = viewModel()

        assertEquals(LocationPermissionState.PlayServicesUnavailable, vm.permission)
        assertFalse(vm.onMyLocationClick())
        assertEquals(LocationNotice.PlayServicesUnavailable, vm.notice)
        assertFalse(vm.state.value.requestPending)
    }

    @Test
    fun `a permission revoked in settings while the app is open is noticed on resume`() {
        environment.granted = GrantedLocation.Precise
        val vm = viewModel()
        assertEquals(LocationPermissionState.Granted(precise = true), vm.permission)

        environment.granted = GrantedLocation.None
        vm.refresh(showRationale = false)

        assertEquals(LocationPermissionState.NotAsked, vm.permission)
        assertFalse(vm.onMyLocationClick())
        assertEquals(LocationPermissionState.DisclosureShown, vm.permission)
    }

    @Test
    fun `precise reduced to approximate in settings is noticed on resume`() {
        environment.granted = GrantedLocation.Precise
        val vm = viewModel()

        environment.granted = GrantedLocation.Approximate
        vm.refresh(showRationale = false)

        assertEquals(LocationPermissionState.Granted(precise = false), vm.permission)
    }

    @Test
    fun `granting in settings after a permanent denial is noticed on resume and closes the notice`() {
        val vm = viewModel()
        vm.askAndWait()
        vm.onPermissionResult(showRationale = true)
        vm.askAndWait()
        vm.onPermissionResult(showRationale = false)
        assertEquals(LocationNotice.DeniedPermanently, vm.notice)

        // Still denied on resume: stays permanent.
        vm.refresh(showRationale = false)
        assertEquals(LocationPermissionState.DeniedPermanently, vm.permission)

        environment.granted = GrantedLocation.Precise
        vm.refresh(showRationale = false)
        assertEquals(LocationPermissionState.Granted(precise = true), vm.permission)
        assertNull(vm.notice)
    }

    @Test
    fun `resuming while the explanation is open leaves it open`() {
        val vm = viewModel()
        vm.onMyLocationClick()

        vm.refresh(showRationale = false)

        assertEquals(LocationPermissionState.DisclosureShown, vm.permission)
    }

    @Test
    fun `after process death a permanent denial is remembered and a grant is read afresh`() {
        val first = viewModel()
        first.askAndWait()
        first.onPermissionResult(showRationale = true)
        first.askAndWait()
        first.onPermissionResult(showRationale = false)

        // Same saved state, new ViewModel: what Android does after killing the process.
        val restored = viewModel()
        assertEquals(LocationPermissionState.DeniedPermanently, restored.permission)
        assertNull(restored.notice)
        assertFalse(restored.state.value.requestPending)

        environment.granted = GrantedLocation.Approximate
        assertEquals(LocationPermissionState.Granted(precise = false), viewModel().permission)
    }

    @Test
    fun `allow only this time looks like not asked on the next start and can be asked again`() {
        val vm = viewModel()
        vm.askAndWait()
        environment.granted = GrantedLocation.Precise
        vm.onPermissionResult(showRationale = false)

        // Android takes a one-time grant back when the app is closed.
        environment.granted = GrantedLocation.None
        val next = LocationPermissionViewModel(SavedStateHandle(), environment, clock)

        assertEquals(LocationPermissionState.NotAsked, next.permission)
        next.onMyLocationClick()
        assertEquals(LocationPermissionState.DisclosureShown, next.permission)
    }

    @Test
    fun `a request android dropped is not a refusal`() {
        val vm = viewModel()
        vm.onMyLocationClick()
        vm.onDisclosureContinue()
        vm.onRequestLaunched()

        vm.onPermissionRequestCancelled()

        assertEquals(LocationPermissionState.NotAsked, vm.permission)
        assertNull(vm.notice)
    }

    @Test
    fun `no position after a tap is said once and can be closed`() {
        environment.granted = GrantedLocation.Precise
        val vm = viewModel()

        vm.onNoFix()
        assertEquals(LocationNotice.NoFix, vm.notice)

        vm.onNoticeDismiss()
        assertNull(vm.notice)
    }
}
