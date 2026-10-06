// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationEnvironment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the user stands with the location permission. */
sealed interface LocationPermissionState {
    /** Never asked, as far as the app can tell. */
    data object NotAsked : LocationPermissionState

    /** The app's own explanation is on screen; the system dialog has not been shown yet. */
    data object DisclosureShown : LocationPermissionState

    /** Denied once in an earlier visit: Android says the app should explain before asking again. */
    data object RationaleNeeded : LocationPermissionState

    data class Granted(val precise: Boolean) : LocationPermissionState

    /** Denied just now. The button stays; a second tap explains and asks once more. */
    data object DeniedOnce : LocationPermissionState

    /** Android no longer shows its dialog. Only the app's page in system Settings can change it. */
    data object DeniedPermanently : LocationPermissionState

    /** Permission granted, but the phone's Location switch is off. */
    data object ServicesOff : LocationPermissionState

    /** No (or outdated) Google Play services: the app cannot get a position on this phone. */
    data object PlayServicesUnavailable : LocationPermissionState
}

/** A short message with at most one action, shown in the map area. Never appears unasked. */
enum class LocationNotice {
    DeniedOnce,
    DeniedPermanently,
    ServicesOff,
    PlayServicesUnavailable,
    Approximate,

    /** As [Approximate], after the user already declined to switch to precise: no action. */
    ApproximateOnly,
    NoFix,
}

data class LocationPermissionUiState(
    val permission: LocationPermissionState = LocationPermissionState.NotAsked,
    val notice: LocationNotice? = null,
    /** The system permission dialog must be shown now. Cleared by [LocationPermissionViewModel.onRequestLaunched]. */
    val requestPending: Boolean = false,
)

/**
 * The location permission as a state machine.
 *
 * Why a state machine: the answer to "may I use location?" is not yes or no. The user can pick
 * approximate, deny once, deny for good, grant "only this time", switch the phone's Location
 * off, or change any of it in system Settings while the app is in the background. So the state
 * is **recomputed every time the screen resumes** ([refresh]) from what Android says now, never
 * remembered as a fact.
 *
 * Rules (Plan v7 §12.3, ADR 0015):
 * - nothing is requested when the app starts or during onboarding. The only way in is
 *   [onMyLocationClick];
 * - the app's own explanation always comes before the system dialog;
 * - no loops: one system dialog per tap at most, and "Not now" is final until the next tap;
 * - a notice is shown only as the answer to something the user did, and can be closed.
 *
 * Android does not tell an app "this permission is denied for good". It is inferred: the
 * request was answered without `shouldShowRequestPermissionRationale` turning true, either
 * after an earlier denial or so fast (under [INSTANT_ANSWER_MILLIS]) that no dialog can have
 * been shown.
 */
@HiltViewModel
class LocationPermissionViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val environment: LocationEnvironment,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LocationPermissionUiState(permission = compute(showRationale = false, previous = restored())),
    )
    val state: StateFlow<LocationPermissionUiState> = _state.asStateFlow()

    /** What the state was when the system dialog was asked for. */
    private var beforeRequest: LocationPermissionState = LocationPermissionState.NotAsked
    private var requestedAtMillis = 0L
    private var upgradeDeclined = false

    /** True from a tap on "my location" until the map has reacted to the resulting grant. */
    private var userAsked = false

    /**
     * The screen is visible again. The user may have been in system Settings in between.
     *
     * @param showRationale `shouldShowRequestPermissionRationale` for fine location, now.
     */
    fun refresh(showRationale: Boolean) {
        val previous = _state.value.permission
        if (previous == LocationPermissionState.DisclosureShown) return
        val next = compute(showRationale, previous)
        update(next) { notice ->
            // A notice about a problem that is gone is closed; others stay as they were.
            notice?.takeIf { it.stillApplies(next) }
        }
    }

    /** The one entry point. Returns true when the permission is in place and the map may act. */
    fun onMyLocationClick(): Boolean {
        when (val current = _state.value.permission) {
            is LocationPermissionState.Granted -> return true
            LocationPermissionState.NotAsked,
            LocationPermissionState.RationaleNeeded,
            LocationPermissionState.DeniedOnce,
            -> {
                beforeRequest = current
                update(LocationPermissionState.DisclosureShown) { null }
            }
            LocationPermissionState.DeniedPermanently -> showNotice(LocationNotice.DeniedPermanently)
            LocationPermissionState.ServicesOff -> showNotice(LocationNotice.ServicesOff)
            LocationPermissionState.PlayServicesUnavailable ->
                showNotice(LocationNotice.PlayServicesUnavailable)
            LocationPermissionState.DisclosureShown -> Unit
        }
        return false
    }

    /** "Continue" on the explanation: now, and only now, the system dialog is requested. */
    fun onDisclosureContinue() {
        if (_state.value.permission != LocationPermissionState.DisclosureShown) return
        userAsked = true
        requestSystemDialog()
    }

    /** "Not now", back, or a tap outside. The map stays usable; nothing is shown or asked. */
    fun onDisclosureNotNow() {
        if (_state.value.permission != LocationPermissionState.DisclosureShown) return
        update(beforeRequest) { null }
    }

    /** "Use precise location" on the approximate hint: asks once more, once. */
    fun onUsePreciseClick() {
        val current = _state.value.permission
        if (current != LocationPermissionState.Granted(precise = false) || upgradeDeclined) return
        beforeRequest = current
        requestSystemDialog()
    }

    /** The system dialog was answered (or Android answered for the user). */
    fun onPermissionResult(showRationale: Boolean) {
        val answeredInstantly = clock.millis() - requestedAtMillis < INSTANT_ANSWER_MILLIS
        val wasUpgrade = beforeRequest is LocationPermissionState.Granted
        val next = when (val computed = compute(showRationale, LocationPermissionState.NotAsked)) {
            LocationPermissionState.NotAsked, LocationPermissionState.RationaleNeeded -> when {
                showRationale -> LocationPermissionState.DeniedOnce
                beforeRequest == LocationPermissionState.NotAsked && !answeredInstantly ->
                    // The dialog was closed without an answer: not a refusal for good.
                    LocationPermissionState.DeniedOnce
                else -> LocationPermissionState.DeniedPermanently
            }
            else -> computed
        }
        if (wasUpgrade && next == LocationPermissionState.Granted(precise = false)) {
            upgradeDeclined = true
        }
        update(next) {
            when (next) {
                LocationPermissionState.DeniedOnce -> LocationNotice.DeniedOnce
                LocationPermissionState.DeniedPermanently -> LocationNotice.DeniedPermanently
                LocationPermissionState.ServicesOff -> LocationNotice.ServicesOff
                LocationPermissionState.PlayServicesUnavailable ->
                    LocationNotice.PlayServicesUnavailable
                LocationPermissionState.Granted(precise = false) -> approximateNotice()
                else -> null
            }
        }
    }

    /** Android dropped the request without asking the user: back to where things stood. */
    fun onPermissionRequestCancelled() {
        if (_state.value.permission == LocationPermissionState.DisclosureShown) {
            update(beforeRequest) { null }
        }
    }

    /** The map found no position (the repository said Unavailable) after the user asked. */
    fun onNoFix() = showNotice(LocationNotice.NoFix)

    fun onNoticeDismiss() = _state.update { it.copy(notice = null) }

    /** Read once by the screen when location starts: should the first fix recentre the map? */
    fun consumeUserAsked(): Boolean = userAsked.also { userAsked = false }

    private fun requestSystemDialog() {
        requestedAtMillis = clock.millis()
        _state.update { it.copy(notice = null, requestPending = true) }
    }

    /** The screen has handed the request to Android. Without this a rotation would ask again. */
    fun onRequestLaunched() = _state.update { it.copy(requestPending = false) }

    private fun showNotice(notice: LocationNotice) = _state.update { it.copy(notice = notice) }

    private fun approximateNotice() =
        if (upgradeDeclined) LocationNotice.ApproximateOnly else LocationNotice.Approximate

    private fun update(next: LocationPermissionState, notice: (LocationNotice?) -> LocationNotice?) {
        savedState[KEY_STATE] = next.savedName()
        _state.update { it.copy(permission = next, notice = notice(it.notice)) }
    }

    /** What Android says now, combined with what only the app can know ([previous]). */
    private fun compute(
        showRationale: Boolean,
        previous: LocationPermissionState,
    ): LocationPermissionState {
        val granted = environment.granted()
        return when {
            !environment.isPlayServicesAvailable() -> LocationPermissionState.PlayServicesUnavailable
            granted != GrantedLocation.None && !environment.isLocationEnabled() ->
                LocationPermissionState.ServicesOff
            granted != GrantedLocation.None ->
                LocationPermissionState.Granted(precise = granted == GrantedLocation.Precise)
            showRationale && previous == LocationPermissionState.DeniedOnce ->
                LocationPermissionState.DeniedOnce
            showRationale -> LocationPermissionState.RationaleNeeded
            previous == LocationPermissionState.DeniedPermanently -> previous
            else -> LocationPermissionState.NotAsked
        }
    }

    /** After the system killed the process: only "denied for good" is worth remembering. */
    private fun restored(): LocationPermissionState =
        if (savedState.get<String>(KEY_STATE) == DENIED_PERMANENTLY) {
            LocationPermissionState.DeniedPermanently
        } else {
            LocationPermissionState.NotAsked
        }

    private fun LocationPermissionState.savedName(): String =
        if (this == LocationPermissionState.DeniedPermanently) DENIED_PERMANENTLY else ""

    private fun LocationNotice.stillApplies(state: LocationPermissionState): Boolean = when (this) {
        LocationNotice.DeniedOnce ->
            state == LocationPermissionState.DeniedOnce || state == LocationPermissionState.RationaleNeeded
        LocationNotice.DeniedPermanently -> state == LocationPermissionState.DeniedPermanently
        LocationNotice.ServicesOff -> state == LocationPermissionState.ServicesOff
        LocationNotice.PlayServicesUnavailable -> state == LocationPermissionState.PlayServicesUnavailable
        LocationNotice.Approximate, LocationNotice.ApproximateOnly ->
            state == LocationPermissionState.Granted(precise = false)
        LocationNotice.NoFix -> state is LocationPermissionState.Granted
    }

    companion object {
        /** Faster than a person can read and answer a dialog. */
        const val INSTANT_ANSWER_MILLIS = 400L
        private const val KEY_STATE = "location_permission"
        private const val DENIED_PERMANENTLY = "denied_permanently"
    }
}
