// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.network.ProbeResult
import com.saferoute.app.core.network.ServerCheck
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.isRetryable
import com.saferoute.app.core.session.AccountResult
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a probe failed, reduced to what the screen says about it. */
sealed interface FailureReason {
    data object NoConnection : FailureReason

    /** 503 or another failure that may pass: "server unavailable, try again". */
    data object Unavailable : FailureReason

    data object SignInNeeded : FailureReason

    data class ServerError(val status: Int, val code: String) : FailureReason

    data class Unexpected(val status: Int?) : FailureReason
}

/** What one probe line shows. */
sealed interface ProbeState {
    data object NotChecked : ProbeState

    data object Checking : ProbeState

    data class Ok(val version: String?) : ProbeState

    data class Failed(val reason: FailureReason) : ProbeState
}

/** The answer of "Who am I": the signed-in account as the server sees it. */
sealed interface WhoAmIState {
    data object NotAsked : WhoAmIState

    data object Asking : WhoAmIState

    data class Known(val role: String, val locale: String) : WhoAmIState

    data object Unavailable : WhoAmIState
}

data class DeveloperCheckUiState(
    val serverConfigured: Boolean,
    val health: ProbeState = ProbeState.NotChecked,
    val readiness: ProbeState = ProbeState.NotChecked,
    val lastRequestId: String? = null,
    /** The session state's name, for example `Ready`. Never a token, uid or phone number. */
    val sessionState: String = "",
    val whoAmI: WhoAmIState = WhoAmIState.NotAsked,
) {
    val checking: Boolean get() = health == ProbeState.Checking || readiness == ProbeState.Checking
    val canCheck: Boolean get() = serverConfigured && !checking
}

/**
 * Debug builds only. Runs the two probes when the screen opens and again on "Check again".
 *
 * `viewModelScope` is a coroutine scope that ends when the screen leaves the back stack, so a
 * check still running then is cancelled instead of updating a screen nobody sees.
 */
@HiltViewModel
class DeveloperCheckViewModel @Inject constructor(
    private val check: ServerCheck,
    private val session: Session,
) : ViewModel() {

    private val _state = MutableStateFlow(DeveloperCheckUiState(serverConfigured = check.isConfigured))
    val state: StateFlow<DeveloperCheckUiState> = _state.asStateFlow()

    init {
        checkNow()
        viewModelScope.launch {
            session.state.collect { sessionState ->
                _state.update { it.copy(sessionState = sessionState.label()) }
            }
        }
    }

    /**
     * "Who am I": asks the API for the signed-in account (`GET /v1/me`) and shows the role and
     * the language the server has. Proves that the phone's ID token is accepted.
     */
    fun whoAmI() {
        if (_state.value.whoAmI == WhoAmIState.Asking) return
        _state.update { it.copy(whoAmI = WhoAmIState.Asking) }
        viewModelScope.launch {
            val answer = when (val result = session.account()) {
                is AccountResult.Loaded -> WhoAmIState.Known(result.account.role, result.account.locale)
                is AccountResult.Unavailable -> WhoAmIState.Unavailable
            }
            _state.update { it.copy(whoAmI = answer) }
        }
    }

    /** Without a configured server nothing is sent: the placeholder address resolves nowhere. */
    fun checkNow() {
        if (!_state.value.canCheck) return
        _state.update { it.copy(health = ProbeState.Checking, readiness = ProbeState.Checking) }
        viewModelScope.launch {
            val health = check.health()
            _state.update {
                it.copy(health = health.toState(), lastRequestId = health.requestId ?: it.lastRequestId)
            }
            val readiness = check.readiness()
            _state.update {
                it.copy(
                    readiness = readiness.toState(),
                    lastRequestId = readiness.requestId ?: it.lastRequestId,
                )
            }
        }
    }
}

/** `Ready`, `SignedOut`, `Blocked(UNDER_18)`, ...: the state's name and nothing else. */
private fun SessionState.label(): String = when (this) {
    is SessionState.Blocked -> "Blocked($reason)"
    is SessionState.Error -> "Error(retryable=$retryable)"
    else -> this::class.simpleName.orEmpty()
}

private fun ProbeResult.toState(): ProbeState = when (this) {
    is ProbeResult.Up -> ProbeState.Ok(version)
    is ProbeResult.Down -> ProbeState.Failed(failure.toReason())
}

private fun ApiFailure.toReason(): FailureReason = when {
    this == ApiFailure.NoConnection -> FailureReason.NoConnection
    isRetryable(this) -> FailureReason.Unavailable
    this is ApiFailure.Unauthorized -> FailureReason.SignInNeeded
    this is ApiFailure.Problem -> FailureReason.ServerError(status, code)
    this is ApiFailure.Unexpected -> FailureReason.Unexpected(status)
    else -> FailureReason.Unexpected(status = null)
}
