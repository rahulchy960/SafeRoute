// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.network.ProbeResult
import com.saferoute.app.core.network.ServerCheck
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.isRetryable
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

data class DeveloperCheckUiState(
    val serverConfigured: Boolean,
    val health: ProbeState = ProbeState.NotChecked,
    val readiness: ProbeState = ProbeState.NotChecked,
    val lastRequestId: String? = null,
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
) : ViewModel() {

    private val _state = MutableStateFlow(DeveloperCheckUiState(serverConfigured = check.isConfigured))
    val state: StateFlow<DeveloperCheckUiState> = _state.asStateFlow()

    init {
        checkNow()
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
