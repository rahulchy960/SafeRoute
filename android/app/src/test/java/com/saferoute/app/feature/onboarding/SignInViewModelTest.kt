// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.auth.FAKE_SMS_CODE
import com.saferoute.app.core.auth.FAKE_VERIFICATION_ID
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.auth.PhoneAuthError
import com.saferoute.app.core.auth.VerificationEvent
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * Phone and code entry against the fake gateway. The phone numbers are made-up patterns that
 * pass the validator; they are never used to send anything (no SMS, no network, no Firebase).
 *
 * Time is virtual: `advanceTimeBy` moves the test's clock, so the 60-second resend wait takes
 * no real time. Runs under Robolectric only because the gateway needs an `Activity` object.
 */
@OptIn(ExperimentalCoroutinesApi::class) // setMain and the virtual-time helpers, test code only
@RunWith(AndroidJUnit4::class)
class SignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val gateway = FakePhoneAuthGateway()
    private val session = FakeSession(SessionState.SignedOut).apply { afterRefresh = SessionState.Ready }
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).get()
    private lateinit var viewModel: SignInViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SignInViewModel(gateway, session)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.state.value

    private fun TestScope.sendCodeTo(input: String = "90000 00001") {
        viewModel.onPhoneChange(input)
        viewModel.submitPhone(activity)
        runCurrent()
    }

    @Test
    fun `starts on the phone step with nothing typed`() {
        assertEquals(SignInUiState(), state)
        assertEquals(SignInStep.PHONE, state.step)
    }

    @Test
    fun `the phone field keeps digits, plus, spaces and dashes only`() {
        viewModel.onPhoneChange("+91 (900)x00-00001;drop")
        assertEquals("+91 90000-00001", state.phoneInput)

        viewModel.onPhoneChange("9".repeat(40))
        assertEquals(17, state.phoneInput.length)
    }

    @Test
    fun `an invalid number shows the error and asks Firebase for nothing`() = scope.runTest {
        for (input in listOf("", "12345", "5000000001", "900000000")) {
            sendCodeTo(input)
            assertTrue(input, state.phoneInvalid)
            assertEquals(SignInStep.PHONE, state.step)
            assertFalse(state.busy)
        }
        assertTrue(gateway.calls.isEmpty())

        // Typing again clears the error.
        viewModel.onPhoneChange("9")
        assertFalse(state.phoneInvalid)
    }

    @Test
    fun `a valid number sends the code and moves to the code step`() = scope.runTest {
        viewModel.onPhoneChange("+91 90000 00001")
        viewModel.submitPhone(activity)
        assertTrue(state.busy)
        runCurrent()

        assertEquals(listOf("startVerification"), gateway.calls)
        assertEquals(SignInStep.CODE, state.step)
        assertFalse(state.busy)
        assertNull(state.error)
        assertEquals(RESEND_WAIT_SECONDS, state.resendInSeconds)
    }

    @Test
    fun `a second tap while sending does nothing`() = scope.runTest {
        viewModel.onPhoneChange("9000000001")
        viewModel.submitPhone(activity)
        viewModel.submitPhone(activity)
        runCurrent()

        assertEquals(listOf("startVerification"), gateway.calls)
    }

    @Test
    fun `the resend wait counts down one second at a time and then allows a resend`() = scope.runTest {
        sendCodeTo()
        assertEquals(60, state.resendInSeconds)
        assertFalse(state.canResend)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, state.resendInSeconds)

        advanceTimeBy(58_000)
        runCurrent()
        assertEquals(1, state.resendInSeconds)
        assertFalse(state.canResend)

        // A resend before the wait is over is ignored.
        viewModel.resend(activity)
        runCurrent()
        assertEquals(listOf("startVerification"), gateway.calls)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, state.resendInSeconds)
        assertTrue(state.canResend)

        viewModel.resend(activity)
        runCurrent()
        assertEquals(listOf("startVerification", "resend"), gateway.calls)
        assertEquals(60, state.resendInSeconds)
        assertEquals(SignInStep.CODE, state.step)
    }

    @Test
    fun `the code field keeps six digits`() = scope.runTest {
        sendCodeTo()
        viewModel.onCodeChange("12a34 5678")
        assertEquals("123456", state.codeInput)
        assertTrue(state.canSubmitCode)

        viewModel.onCodeChange("123")
        assertFalse(state.canSubmitCode)
    }

    @Test
    fun `the right code signs in and tells the session`() = scope.runTest {
        sendCodeTo()
        viewModel.onCodeChange(FAKE_SMS_CODE)
        viewModel.submitCode()
        runCurrent()

        assertEquals(listOf("startVerification", "verifyCode"), gateway.calls)
        assertEquals(listOf("refresh"), session.calls)
        assertEquals(SessionState.Ready, session.state.value)
        // Nothing typed is kept once signed in, and the countdown has stopped.
        assertEquals("", state.phoneInput)
        assertEquals("", state.codeInput)
        advanceUntilIdle()
        assertEquals(listOf("refresh"), session.calls)
    }

    @Test
    fun `a wrong code shows the error, clears the field and leaves the session alone`() = scope.runTest {
        sendCodeTo()
        viewModel.onCodeChange("111111")
        viewModel.submitCode()
        runCurrent()

        assertEquals(PhoneAuthError.WRONG_CODE, state.error)
        assertEquals("", state.codeInput)
        assertEquals(SignInStep.CODE, state.step)
        assertFalse(state.busy)
        assertTrue(session.calls.isEmpty())

        // Typing again clears the error; the right code then works.
        viewModel.onCodeChange("0")
        assertNull(state.error)
        viewModel.onCodeChange(FAKE_SMS_CODE)
        viewModel.submitCode()
        runCurrent()
        assertEquals(listOf("refresh"), session.calls)
    }

    @Test
    fun `an incomplete code is not sent`() = scope.runTest {
        sendCodeTo()
        viewModel.onCodeChange("123")
        viewModel.submitCode()
        runCurrent()

        assertEquals(listOf("startVerification"), gateway.calls)
    }

    @Test
    fun `every verification failure is shown on the step where it happened`() = scope.runTest {
        for (error in PhoneAuthError.entries) {
            gateway.nextVerification = listOf(VerificationEvent.Failed(error))
            sendCodeTo()

            assertEquals(error, state.error)
            assertEquals(SignInStep.PHONE, state.step)
            assertFalse(state.busy)
        }
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `every failure while checking the code is shown and nothing leaks into the state`() = scope.runTest {
        sendCodeTo()
        for (error in PhoneAuthError.entries) {
            gateway.verifyError = error
            viewModel.onCodeChange("654321")
            viewModel.submitCode()
            runCurrent()

            assertEquals(error, state.error)
            // The state holds an enum value: no SDK message, no number, no code.
            assertFalse(state.toString().contains("654321"))
            assertEquals("", state.codeInput)
        }
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `when Android reads the SMS itself the user is signed in without typing`() = scope.runTest {
        gateway.nextVerification = listOf(VerificationEvent.SignedIn)
        sendCodeTo()

        assertEquals(listOf("startVerification"), gateway.calls)
        assertEquals(listOf("refresh"), session.calls)
        assertEquals(SessionState.Ready, session.state.value)
    }

    @Test
    fun `auto-retrieval after the code was sent also signs in`() = scope.runTest {
        gateway.nextVerification = listOf(
            VerificationEvent.CodeSent(FAKE_VERIFICATION_ID),
            VerificationEvent.SignedIn,
        )
        sendCodeTo()

        assertEquals(listOf("refresh"), session.calls)
    }

    @Test
    fun `change number goes back, stops the countdown and forgets the verification`() = scope.runTest {
        sendCodeTo()
        viewModel.onCodeChange("123456")

        viewModel.changeNumber()
        advanceUntilIdle()

        assertEquals(SignInStep.PHONE, state.step)
        assertEquals("", state.codeInput)
        assertEquals(0, state.resendInSeconds)
        // The old verification is gone: neither a code nor a resend does anything.
        viewModel.submitCode()
        viewModel.resend(activity)
        runCurrent()
        assertEquals(listOf("startVerification"), gateway.calls)
    }
}
