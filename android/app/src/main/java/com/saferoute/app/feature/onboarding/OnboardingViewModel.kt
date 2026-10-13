// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.diagnostics.DiagnosticsPreferences
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Connects the app's root and the onboarding screens to the [Session].
 *
 * It lives as long as the activity (it is created at the root of the UI), so the session check
 * runs once when the app starts and not again when the phone is rotated.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val session: Session,
    private val diagnostics: DiagnosticsPreferences,
) : ViewModel() {

    val state: StateFlow<SessionState> = session.state

    /**
     * Whether the welcome screen was passed. Null until the stored flags have been read, so the
     * welcome screen does not flash for someone who has already seen it.
     *
     * `stateIn` turns the Flow of stored flags into a StateFlow the screen can read at once.
     */
    val welcomeSeen: StateFlow<Boolean?> = session.flags
        .map<_, Boolean?> { it.welcomeSeen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the choice about crash reports has been put to this person. Null until the
     * stored flags have been read.
     */
    val crashReportsAsked: StateFlow<Boolean?> = diagnostics.settings
        .map<_, Boolean?> { it.asked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refresh()
    }

    /** Also the "Try again" button. */
    fun refresh() {
        viewModelScope.launch { session.refresh() }
    }

    fun onWelcomeContinue() {
        viewModelScope.launch { session.markWelcomeSeen() }
    }

    fun onAdultConfirmed() {
        viewModelScope.launch { session.confirmAdult() }
    }

    /** Called only after the confirmation dialog: the choice cannot be undone in the app. */
    fun onUnder18Confirmed() {
        viewModelScope.launch { session.declareUnder18() }
    }

    /** The onboarding step about crash reports: [enabled] is what the box said at "Continue". */
    fun onCrashReportsChoice(enabled: Boolean) {
        viewModelScope.launch { diagnostics.setCrashReports(enabled) }
    }

    /** [noticeLocale] is the language the notice was shown in when "I agree" was tapped. */
    fun onNoticeAccepted(noticeLocale: String) {
        viewModelScope.launch { session.acceptNotice(noticeLocale) }
    }
}
