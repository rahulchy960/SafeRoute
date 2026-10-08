// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.feature.emergency.EmergencyNotificationController
import com.saferoute.app.feature.emergency.EmergencyShortcutPreferences
import com.saferoute.app.feature.emergency.NotificationBlock
import com.saferoute.app.feature.emergency.NotificationGate
import com.saferoute.app.feature.emergency.TileAddResult
import com.saferoute.app.feature.emergency.TileAdder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Settings says after "Add the SOS tile" was tapped. */
enum class TileNotice {
    Added,
    AlreadyAdded,

    /** Android did not add it (or can not ask on this version): show how to do it by hand. */
    ShowSteps,
}

/** A message about the notification shortcut, shown only as the answer to something the user did. */
enum class NotificationNotice {
    /** Refused just now; Android will ask again on the next try. */
    DeniedOnce,

    /** Refused, and Android no longer shows its dialog: only system settings can change it. */
    DeniedForGood,

    /** Notifications are off for the whole app. */
    AppBlocked,

    /** This one notification category is off. */
    ChannelBlocked,
}

data class NotificationShortcutUiState(
    /** The user's switch. */
    val enabled: Boolean = false,
    /** While the switch is on: what, if anything, stops Android from showing the notification. */
    val block: NotificationBlock = NotificationBlock.None,
    /** The app's own explanation is on screen; nothing has been requested yet. */
    val explaining: Boolean = false,
    val notice: NotificationNotice? = null,
    /** The system permission dialog must be shown now. Cleared by [EmergencyShortcutsViewModel.onRequestLaunched]. */
    val requestPending: Boolean = false,
)

/**
 * The "Emergency shortcuts" section of Settings: adding the Quick Settings tile, and the
 * optional pinned notification.
 *
 * The notification's permission as a small state machine (the same rules as for location,
 * ADR 0015):
 * - nothing is requested when the app or this screen opens. The only way in is the switch;
 * - the app's own explanation comes before Android's dialog;
 * - one system dialog per tap at most, no loops;
 * - what Android allows is read again every time the screen comes back ([refresh]), because
 *   the user can change it in system settings at any moment.
 *
 * Stored: the switch (and, elsewhere, the "offer shown" flag). Nothing else.
 */
@HiltViewModel
class EmergencyShortcutsViewModel @Inject constructor(
    private val tileAdder: TileAdder,
    private val preferences: EmergencyShortcutPreferences,
    private val gate: NotificationGate,
    private val controller: EmergencyNotificationController,
) : ViewModel() {

    private val _tileNotice = MutableStateFlow<TileNotice?>(null)
    val tileNotice: StateFlow<TileNotice?> = _tileNotice.asStateFlow()

    private val _notification = MutableStateFlow(NotificationShortcutUiState())
    val notification: StateFlow<NotificationShortcutUiState> = _notification.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.notificationEnabled.collect { enabled ->
                _notification.update { it.copy(enabled = enabled, block = blockIf(enabled)) }
            }
        }
    }

    private fun blockIf(enabled: Boolean) = if (enabled) gate.block() else NotificationBlock.None

    /** Only ever called from a tap: Android wants the request made in context. */
    fun onAddTileClick() {
        tileAdder.requestAdd { result ->
            _tileNotice.value = when (result) {
                TileAddResult.Added -> TileNotice.Added
                TileAddResult.AlreadyAdded -> TileNotice.AlreadyAdded
                TileAddResult.NotAdded -> TileNotice.ShowSteps
            }
        }
    }

    fun onTileNoticeDismiss() {
        _tileNotice.value = null
    }

    /** The screen is visible again: the user may have been in system settings. */
    fun refresh() {
        _notification.update { it.copy(block = blockIf(it.enabled)) }
        controller.syncInBackground()
    }

    /** The switch was tapped. Turning on explains first; turning off just does it. */
    fun onNotificationToggle(on: Boolean) {
        if (on) {
            _notification.update { it.copy(explaining = true, notice = null) }
        } else {
            _notification.update { it.copy(explaining = false, notice = null, requestPending = false) }
            store(enabled = false)
        }
    }

    /** "Not now" on the explanation: nothing is requested and nothing changes. */
    fun onExplanationDismiss() {
        _notification.update { it.copy(explaining = false) }
    }

    /** "Continue" on the explanation: now, and only now, Android may be asked. */
    fun onExplanationContinue() {
        if (!_notification.value.explaining) return
        _notification.update { it.copy(explaining = false) }
        when (gate.block()) {
            NotificationBlock.PermissionMissing -> _notification.update { it.copy(requestPending = true) }
            NotificationBlock.AppBlocked -> showNotice(NotificationNotice.AppBlocked)
            NotificationBlock.ChannelBlocked -> showNotice(NotificationNotice.ChannelBlocked)
            NotificationBlock.None -> store(enabled = true)
        }
    }

    /** The screen has shown the system dialog; it must not be shown a second time. */
    fun onRequestLaunched() {
        _notification.update { it.copy(requestPending = false) }
    }

    /**
     * Android's dialog was answered (or closed).
     *
     * @param showRationale `shouldShowRequestPermissionRationale` afterwards. After a refusal
     * it is true while Android would still ask again, and false once it will not.
     */
    fun onPermissionResult(showRationale: Boolean) {
        when (gate.block()) {
            NotificationBlock.None -> store(enabled = true)
            NotificationBlock.AppBlocked -> showNotice(NotificationNotice.AppBlocked)
            NotificationBlock.ChannelBlocked -> showNotice(NotificationNotice.ChannelBlocked)
            NotificationBlock.PermissionMissing ->
                showNotice(if (showRationale) NotificationNotice.DeniedOnce else NotificationNotice.DeniedForGood)
        }
    }

    fun onNotificationNoticeDismiss() {
        _notification.update { it.copy(notice = null) }
    }

    private fun showNotice(notice: NotificationNotice) {
        _notification.update { it.copy(notice = notice) }
    }

    private fun store(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setNotificationEnabled(enabled)
            // Posts or removes at once; the controller also follows the switch by itself.
            controller.sync()
        }
    }
}
