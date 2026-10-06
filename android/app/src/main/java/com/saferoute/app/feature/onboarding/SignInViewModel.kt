// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.auth.PhoneAuthError
import com.saferoute.app.core.auth.PhoneAuthGateway
import com.saferoute.app.core.auth.SignInResult
import com.saferoute.app.core.auth.VerificationEvent
import com.saferoute.app.core.auth.normaliseIndianMobile
import com.saferoute.app.core.session.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long the user waits before a new code can be requested. */
const val RESEND_WAIT_SECONDS = 60

const val SMS_CODE_LENGTH = 6

/** Longest input the phone field accepts: `+91`, ten digits and a few spaces or dashes. */
private const val MAX_PHONE_INPUT = 17

enum class SignInStep { PHONE, CODE }

data class SignInUiState(
    val step: SignInStep = SignInStep.PHONE,
    val phoneInput: String = "",
    /** The typed number is not an Indian mobile number. Shown after "Send code" only. */
    val phoneInvalid: Boolean = false,
    val codeInput: String = "",
    /** A request is on its way: sending the SMS, or checking the code. */
    val busy: Boolean = false,
    val error: PhoneAuthError? = null,
    /** Seconds until "Send a new code" is allowed; 0 means now. */
    val resendInSeconds: Int = 0,
) {
    val canSubmitCode: Boolean get() = codeInput.length == SMS_CODE_LENGTH && !busy
    val canResend: Boolean get() = resendInSeconds == 0 && !busy
}

/**
 * Phone number and SMS code entry.
 *
 * The phone number, the code and Firebase's verification id live in this object's memory only.
 * They are never written to disk, to saved instance state or to a log. If Android kills the app
 * on the code screen, the user comes back to the phone screen and asks for a new code.
 *
 * When sign-in succeeds it only tells the [Session]; the session then decides what comes next
 * (create the account, show Home, ...), and navigation follows the session state.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val gateway: PhoneAuthGateway,
    private val session: Session,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private var phoneE164: String? = null
    private var verificationId: String? = null
    private var verification: Job? = null
    private var resendTimer: Job? = null

    fun onPhoneChange(input: String) {
        val cleaned = input.filter { it.isDigit() || it in "+ -" }.take(MAX_PHONE_INPUT)
        _state.update { it.copy(phoneInput = cleaned, phoneInvalid = false, error = null) }
    }

    /** "Send code". [activity] is what Firebase needs for its app check (see PhoneAuthGateway). */
    fun submitPhone(activity: Activity) {
        if (_state.value.busy) return
        val normalised = normaliseIndianMobile(_state.value.phoneInput)
        if (normalised == null) {
            _state.update { it.copy(phoneInvalid = true, error = null) }
            return
        }
        phoneE164 = normalised
        startVerification { gateway.startVerification(normalised, activity) }
    }

    /** "Send a new code", allowed once the wait is over. */
    fun resend(activity: Activity) {
        val phone = phoneE164 ?: return
        if (!_state.value.canResend) return
        startVerification { gateway.resend(phone, activity) }
    }

    private fun startVerification(request: () -> kotlinx.coroutines.flow.Flow<VerificationEvent>) {
        verification?.cancel()
        _state.update { it.copy(busy = true, error = null, phoneInvalid = false) }
        verification = viewModelScope.launch {
            request().collect { event ->
                when (event) {
                    is VerificationEvent.CodeSent -> {
                        verificationId = event.verificationId
                        _state.update {
                            it.copy(step = SignInStep.CODE, busy = false, codeInput = "", error = null)
                        }
                        startResendTimer()
                    }
                    VerificationEvent.SignedIn -> onSignedIn()
                    is VerificationEvent.Failed ->
                        _state.update { it.copy(busy = false, error = event.error) }
                }
            }
        }
    }

    fun onCodeChange(input: String) {
        val digits = input.filter(Char::isDigit).take(SMS_CODE_LENGTH)
        _state.update { it.copy(codeInput = digits, error = null) }
    }

    /** "Verify". */
    fun submitCode() {
        val id = verificationId ?: return
        val current = _state.value
        if (!current.canSubmitCode) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = gateway.verifyCode(id, current.codeInput)) {
                SignInResult.Success -> onSignedIn()
                is SignInResult.Failure ->
                    // The wrong code is cleared; the message never repeats it.
                    _state.update { it.copy(busy = false, codeInput = "", error = result.error) }
            }
        }
    }

    /** "Change number", or back from the code screen. */
    fun changeNumber() {
        verification?.cancel()
        resendTimer?.cancel()
        verificationId = null
        phoneE164 = null
        _state.update {
            it.copy(step = SignInStep.PHONE, codeInput = "", busy = false, error = null, resendInSeconds = 0)
        }
    }

    private suspend fun onSignedIn() {
        resendTimer?.cancel()
        // Forget what is no longer needed before anything else happens.
        phoneE164 = null
        verificationId = null
        _state.update { it.copy(phoneInput = "", codeInput = "", busy = true, error = null) }
        session.refresh()
    }

    /** Counts down once a second. `delay` suspends without blocking; tests use virtual time. */
    private fun startResendTimer() {
        resendTimer?.cancel()
        resendTimer = viewModelScope.launch {
            for (seconds in RESEND_WAIT_SECONDS downTo 1) {
                _state.update { it.copy(resendInSeconds = seconds) }
                delay(1_000)
            }
            _state.update { it.copy(resendInSeconds = 0) }
        }
    }
}
