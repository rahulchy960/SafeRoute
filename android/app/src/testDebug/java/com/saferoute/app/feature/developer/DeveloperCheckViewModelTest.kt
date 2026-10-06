// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.developer

import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.network.ProbeResult
import com.saferoute.app.core.network.ServerCheck
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.session.AccountResult
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The developer screen's states. Debug-only test (src/testDebug): the class under test exists
 * only in debug builds.
 */
@OptIn(ExperimentalCoroutinesApi::class) // setMain/resetMain, test code only
class DeveloperCheckViewModelTest {

    /** Answers with whatever the test set; counts the calls. */
    private class FakeServerCheck(
        override val isConfigured: Boolean = true,
        var health: ProbeResult = ProbeResult.Up("0.1.0", "req-health-1"),
        var readiness: ProbeResult = ProbeResult.Up(null, "req-ready-1"),
    ) : ServerCheck {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun health(): ProbeResult {
            calls++
            gate?.await()
            return health
        }

        override suspend fun readiness(): ProbeResult {
            calls++
            return readiness
        }
    }

    // viewModelScope runs on the main dispatcher, which a JVM test doesn't have: replace it.
    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val session = FakeSession(SessionState.Ready)

    @Suppress("TestFunctionName") // reads like the constructor it wraps
    private fun DeveloperCheckViewModel(check: ServerCheck) =
        DeveloperCheckViewModel(check, session, locationEnvironment, location, Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC))

    private val locationEnvironment = FakeLocationEnvironment()
    private val location = FakeLocationRepository()

    @Test
    fun `location is shown as permission, switch and age bucket, never as values`() = runTest {
        val viewModel = DeveloperCheckViewModel(FakeServerCheck())
        assertEquals("None · on · no fix", viewModel.state.value.location)

        locationEnvironment.granted = GrantedLocation.Precise
        // Distinctive digits: none of them may reach the screen.
        location.state.value = LocationState.Fix(
            fakeFix(position = LatLng(12.345678, 98.765432), accuracyMeters = 13.57f, timeMillis = 95_000),
        )
        assertEquals("Precise · on · fix <10 s", viewModel.state.value.location)

        locationEnvironment.granted = GrantedLocation.Approximate
        locationEnvironment.locationEnabled = false
        location.state.value = LocationState.Stale(fakeFix(timeMillis = 0, isApproximate = true), ageSeconds = 100)
        assertEquals("Approximate · off · fix older", viewModel.state.value.location)

        listOf("12.3", "98.7", "13.5", "95").forEach { assertFalse(viewModel.state.value.toString().contains(it)) }
    }

    @Test
    fun `the session state is shown by name and follows changes`() = runTest {
        val viewModel = DeveloperCheckViewModel(FakeServerCheck())
        assertEquals("Ready", viewModel.state.value.sessionState)

        session.setState(SessionState.SignedOut)
        assertEquals("SignedOut", viewModel.state.value.sessionState)

        session.setState(SessionState.Blocked(BlockReason.UNDER_18))
        assertEquals("Blocked(UNDER_18)", viewModel.state.value.sessionState)

        session.setState(SessionState.Error(retryable = true))
        assertEquals("Error(retryable=true)", viewModel.state.value.sessionState)
    }

    @Test
    fun `who am I shows the role and language from the server, never the phone number`() = runTest {
        val viewModel = DeveloperCheckViewModel(FakeServerCheck())
        assertEquals(WhoAmIState.NotAsked, viewModel.state.value.whoAmI)

        viewModel.whoAmI()

        assertEquals(WhoAmIState.Known(role = "user", locale = "en"), viewModel.state.value.whoAmI)
        assertFalse(viewModel.state.value.toString().contains("+91"))
        assertEquals(listOf("account"), session.calls)
    }

    @Test
    fun `who am I says so when the server cannot be asked`() = runTest {
        session.accountResult = AccountResult.Unavailable(retryable = true)
        val viewModel = DeveloperCheckViewModel(FakeServerCheck())

        viewModel.whoAmI()

        assertEquals(WhoAmIState.Unavailable, viewModel.state.value.whoAmI)
    }

    @Test
    fun `a reachable server shows its version, readiness and the last request id`() = runTest {
        val viewModel = DeveloperCheckViewModel(FakeServerCheck())

        val state = viewModel.state.value
        assertTrue(state.serverConfigured)
        assertEquals(ProbeState.Ok("0.1.0"), state.health)
        assertEquals(ProbeState.Ok(null), state.readiness)
        assertEquals("req-ready-1", state.lastRequestId)
        assertTrue(state.canCheck)
    }

    @Test
    fun `without a configured server nothing is sent`() = runTest {
        val check = FakeServerCheck(isConfigured = false)
        val viewModel = DeveloperCheckViewModel(check)

        viewModel.checkNow()

        val state = viewModel.state.value
        assertFalse(state.serverConfigured)
        assertEquals(ProbeState.NotChecked, state.health)
        assertEquals(ProbeState.NotChecked, state.readiness)
        assertFalse(state.canCheck)
        assertEquals(0, check.calls)
    }

    @Test
    fun `no connection is shown as such and has no request id`() = runTest {
        val down = ProbeResult.Down(ApiFailure.NoConnection)
        val viewModel = DeveloperCheckViewModel(FakeServerCheck(health = down, readiness = down))

        val state = viewModel.state.value
        assertEquals(ProbeState.Failed(FailureReason.NoConnection), state.health)
        assertEquals(ProbeState.Failed(FailureReason.NoConnection), state.readiness)
        assertEquals(null, state.lastRequestId)
    }

    @Test
    fun `503 is shown as unavailable with the server's request id`() = runTest {
        val unavailable = ProbeResult.Down(
            ApiFailure.Problem(503, "db_unavailable", "Service Unavailable", "Not ready.", "req-503-abcdef"),
        )
        val viewModel = DeveloperCheckViewModel(FakeServerCheck(readiness = unavailable))

        val state = viewModel.state.value
        assertEquals(ProbeState.Ok("0.1.0"), state.health)
        assertEquals(ProbeState.Failed(FailureReason.Unavailable), state.readiness)
        assertEquals("req-503-abcdef", state.lastRequestId)
    }

    @Test
    fun `other failures keep their status and code`() = runTest {
        val viewModel = DeveloperCheckViewModel(
            FakeServerCheck(
                health = ProbeResult.Down(ApiFailure.Unexpected(502, "req-502-abcdef")),
                readiness = ProbeResult.Down(
                    ApiFailure.Problem(500, "internal_error", "Internal Server Error", "d", "req-500"),
                ),
            ),
        )

        val state = viewModel.state.value
        assertEquals(ProbeState.Failed(FailureReason.Unexpected(502)), state.health)
        assertEquals(ProbeState.Failed(FailureReason.ServerError(500, "internal_error")), state.readiness)
    }

    @Test
    fun `while checking the button is off and a second tap sends nothing`() = runTest {
        val check = FakeServerCheck().apply { gate = CompletableDeferred() }
        val viewModel = DeveloperCheckViewModel(check)

        assertEquals(ProbeState.Checking, viewModel.state.value.health)
        assertFalse(viewModel.state.value.canCheck)
        viewModel.checkNow()
        assertEquals(1, check.calls)

        check.gate?.complete(Unit)
        assertEquals(ProbeState.Ok("0.1.0"), viewModel.state.value.health)
        assertEquals(2, check.calls)
    }

    @Test
    fun `check again runs both probes again`() = runTest {
        val check = FakeServerCheck()
        val viewModel = DeveloperCheckViewModel(check)
        check.health = ProbeResult.Down(ApiFailure.NoConnection)

        viewModel.checkNow()

        assertEquals(4, check.calls)
        assertEquals(ProbeState.Failed(FailureReason.NoConnection), viewModel.state.value.health)
    }
}
