// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.emergency.CountdownTone
import com.saferoute.app.core.emergency.SOS_COUNTDOWN
import com.saferoute.app.core.emergency.SmsLimitNotice
import com.saferoute.app.core.emergency.SmsLimitNotices
import com.saferoute.app.core.emergency.SosAlertStatus
import com.saferoute.app.core.emergency.SosDispatch
import com.saferoute.app.core.emergency.SosEntryPoint
import com.saferoute.app.core.emergency.SosHaptics
import com.saferoute.app.core.emergency.SosLocationReport
import com.saferoute.app.core.emergency.SosRecovery
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.core.emergency.SosSettingsStore
import com.saferoute.app.core.emergency.SosTrail
import com.saferoute.app.core.emergency.StartSos
import com.saferoute.app.core.emergency.StartSosResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why the emergency screen was opened. */
enum class SosOpenMode {
    /** A shortcut or a notification: show what is going on, or the emergency options. */
    OPEN,

    /**
     * Start an SOS: the hold in the app was completed, or the tile, the widget or the
     * notification's "Start SOS" was tapped. The intent says which.
     */
    START,

    /** "I'm safe" on the notification: show the emergency and ask to confirm. */
    SAFE,

    /** A practice run. Nothing real happens. */
    PRACTICE,
}

/** What the emergency screen shows. One at a time. */
sealed interface SosUi {
    /** Nothing is running: the dialog with Call 112. */
    data object Options : SosUi

    /**
     * A start was asked for and SOS alerts are not set up: the same dialog, saying so, with
     * the way to the setup and a practice run. No countdown.
     */
    data object NotSetUp : SosUi

    data class Countdown(val secondsLeft: Int, val practice: Boolean = false) : SosUi

    /**
     * @property recovered true when the app found this emergency after it had been closed:
     *   the screen then says "SOS is still active" first.
     * @property confirmingSafe the "I'm safe" confirmation is open.
     * @property alerts what may be said about the messages: counts, never a name or a number.
     * @property canTellContacts someone was alerted, so the confirmation offers to tell them.
     * @property tellContacts the state of that checkbox.
     */
    data class Active(
        val location: SosLocationReport,
        val notificationsOff: Boolean,
        val alerts: SosAlertStatus = SosAlertStatus.NotEnabled,
        val canTellContacts: Boolean = false,
        val tellContacts: Boolean = true,
        val recovered: Boolean = false,
        val confirmingSafe: Boolean = false,
        val practice: Boolean = false,
    ) : SosUi

    /** A countdown whose end passed while the app was closed: Send now or Cancel. */
    data object AskSendOrCancel : SosUi

    /** The practice run is over. */
    data object PracticeFinished : SosUi

    /** Nothing more to show: the activity closes. */
    data object Closed : SosUi
}

/** Was a message sent, or handed to the SMS app? Then "I'm safe" may tell those contacts. */
private fun SosAlertStatus.someoneWasTold(): Boolean = when (this) {
    is SosAlertStatus.Automatic -> summary.sent > 0
    is SosAlertStatus.Composer -> contacts > 0
    SosAlertStatus.NotEnabled, SosAlertStatus.Preparing, SosAlertStatus.NoContacts -> false
}

private enum class Practice { NONE, COUNTDOWN, ACTIVE, FINISHED }

private data class Local(
    val asking: Boolean = false,
    val recovered: Boolean = false,
    val confirmingSafe: Boolean = false,
    val closed: Boolean = false,
    /** A start was refused by the consent gate. */
    val notSetUp: Boolean = false,
    val practice: Practice = Practice.NONE,
    val practiceSeconds: Int = 0,
    val practiceConfirming: Boolean = false,
    /** "Tell my contacts", on by default. */
    val tellContacts: Boolean = true,
    /** Changes when the screen comes back, so that the location line is read again. */
    val refresh: Int = 0,
)

/**
 * The state of the emergency screen. It decides nothing about the emergency: every button is
 * passed to [SosRunner], which belongs to the app and goes on when this screen is gone.
 *
 * Commands run in the APP's scope, not in this ViewModel's: a countdown that was just started
 * must not be half-started because the screen was rotated or closed in that instant. (The
 * same lesson as the sign-in flow, P009d.)
 *
 * A practice run lives only here, in memory: it never touches the runner, the database, the
 * contacts or the location. It vibrates like the real one so that it can be felt.
 */
@HiltViewModel
class SosViewModel @Inject constructor(
    private val runner: SosRunner,
    private val trail: SosTrail,
    private val gate: NotificationGate,
    private val haptics: SosHaptics,
    private val dispatch: SosDispatch,
    private val startSos: StartSos,
    private val notices: SmsLimitNotices,
    private val settings: SosSettingsStore,
    private val tone: CountdownTone,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /**
     * What a build that limits its recipients (a debug build) does with an alert; null in
     * every other build. Shown on the countdown and the active screen, never in practice.
     */
    val smsNotice: StateFlow<SmsLimitNotice?> =
        flow { emit(notices.current()) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val local = MutableStateFlow(Local())

    private val _safeRequested = MutableStateFlow(false)

    /** A notification's "I'm safe" was used; the screen answers with its unlock-then-ask. */
    val safeRequested: StateFlow<Boolean> = _safeRequested.asStateFlow()

    fun onSafeRequestHandled() {
        _safeRequested.value = false
    }
    private var practiceTimer: Job? = null
    private var opened = false

    val ui: StateFlow<SosUi> = combine(runner.state, trail.state, dispatch.status, local) { run, _, alerts, here ->
        when {
            here.closed -> SosUi.Closed
            // A real emergency always wins over a practice run and over the question.
            run is SosRunState.Countdown -> SosUi.Countdown(run.secondsLeft)
            run is SosRunState.Active -> SosUi.Active(
                location = trail.report(),
                notificationsOff = gate.block() != NotificationBlock.None,
                alerts = alerts,
                canTellContacts = alerts.someoneWasTold(),
                tellContacts = here.tellContacts,
                recovered = here.recovered,
                confirmingSafe = here.confirmingSafe,
            )
            here.asking -> SosUi.AskSendOrCancel
            here.practice == Practice.COUNTDOWN -> SosUi.Countdown(here.practiceSeconds, practice = true)
            here.practice == Practice.ACTIVE -> SosUi.Active(
                location = SosLocationReport.Unavailable,
                notificationsOff = false,
                confirmingSafe = here.practiceConfirming,
                practice = true,
            )
            here.practice == Practice.FINISHED -> SosUi.PracticeFinished
            here.notSetUp -> SosUi.NotSetUp
            else -> SosUi.Options
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SosUi.Options)

    /**
     * Called once when the screen is created for a new reason ([fresh]) and again each time it
     * comes back to the front. A recreated screen (rotation, process restart) never starts a
     * new emergency: it only looks at what is there.
     *
     * @param entryPoint what the user touched, for [SosOpenMode.START]; stored with the record.
     */
    fun onShown(mode: SosOpenMode, fresh: Boolean, entryPoint: SosEntryPoint = SosEntryPoint.IN_APP) {
        local.value = local.value.copy(refresh = local.value.refresh + 1)
        val first = !opened
        opened = true
        if (first && fresh && mode == SosOpenMode.PRACTICE) {
            startPractice()
            return
        }
        appScope.launch {
            // Every way in ends here, in the one use case, which asks the consent gate
            // first: the hold, the tile, the widget and the notification cannot differ, and
            // none of them can start an SOS the user has not agreed to.
            if (first && fresh && mode == SosOpenMode.START) {
                val refused = startSos(entryPoint) == StartSosResult.NotSetUp
                if (refused) local.value = local.value.copy(notSetUp = true)
            }
            when (runner.resume()) {
                is SosRecovery.AskSendOrCancel -> local.value = local.value.copy(asking = true)
                is SosRecovery.StillActive -> local.value = local.value.copy(recovered = true)
                is SosRecovery.ResumeCountdown, SosRecovery.Nothing -> Unit
            }
            // "I'm safe" on a notification only ASKS: the screen takes it through the unlock
            // first, exactly like a tap on its own button (it may be showing over the lock
            // screen, and a stranger holding the phone must not be able to end an SOS).
            if (first && fresh && mode == SosOpenMode.SAFE) _safeRequested.value = true
            // The screen came to the front again while an SOS is sending: the user is looking,
            // so an unsent alert gets a try now. Not at the first showing: the alert is only
            // just leaving then, and a try that is in flight is never doubled anyway.
            if (!first) dispatch.retryNow()
        }
    }

    /** Cancel during the countdown, or on the question. Nothing was sent; nothing remains. */
    fun onCancel() {
        if (local.value.practice != Practice.NONE) {
            endPractice()
            return
        }
        appScope.launch {
            runner.cancel()
            local.value = local.value.copy(asking = false, closed = true)
        }
    }

    /** "Send now" on the question. */
    fun onSendNow() {
        appScope.launch {
            runner.sendNow()
            local.value = local.value.copy(asking = false)
        }
    }

    /** "Continue" on "SOS is still active". */
    fun onContinue() {
        local.value = local.value.copy(recovered = false)
    }

    /** "I'm safe" was tapped (after the unlock, on a locked phone): ask to confirm. */
    fun onSafeClick() {
        local.value = if (local.value.practice == Practice.ACTIVE) {
            local.value.copy(practiceConfirming = true)
        } else {
            local.value.copy(confirmingSafe = true, recovered = false)
        }
    }

    fun onSafeDismiss() {
        local.value = local.value.copy(confirmingSafe = false, practiceConfirming = false)
    }

    /** The "Tell my contacts" checkbox of the confirmation. */
    fun onTellContactsChange(tell: Boolean) {
        local.value = local.value.copy(tellContacts = tell)
    }

    /** "Open SMS app again" on the active screen (composer mode). */
    fun onOpenComposer() = dispatch.reopenComposer()

    /**
     * "Send again now" on the active screen: one try at once for every unsent alert. In the
     * app's scope, like every command: the try must not end because this screen went away.
     */
    fun onRetryNow() {
        appScope.launch { dispatch.retryNow() }
    }

    fun onSafeConfirm() {
        if (local.value.practice == Practice.ACTIVE) {
            local.value = local.value.copy(practice = Practice.FINISHED, practiceConfirming = false)
            return
        }
        // Only when someone was told anything, and the box is still ticked.
        val tell = local.value.tellContacts && dispatch.status.value.someoneWasTold()
        appScope.launch {
            runner.markSafe(tellContacts = tell)
            local.value = local.value.copy(confirmingSafe = false, closed = true)
        }
    }

    /** "Practice SOS" on the dialog that says alerts are not set up. */
    fun onPractice() = startPractice()

    /** Close on the options dialog or on "Practice finished". */
    fun onClose() {
        endPractice()
    }

    private fun startPractice() {
        // Never over a real emergency.
        if (runner.state.value != SosRunState.Idle) return
        practiceTimer?.cancel()
        practiceTimer = viewModelScope.launch {
            // A practice run sounds like the real one, so that the setting can be tried.
            val sound = runCatching { settings.current().countdownSound }.getOrDefault(false)
            for (seconds in SOS_COUNTDOWN.seconds.toInt() downTo 1) {
                local.value = local.value.copy(practice = Practice.COUNTDOWN, practiceSeconds = seconds)
                haptics.countdownTick()
                if (sound) tone.play()
                delay(PRACTICE_SECOND_MILLIS)
            }
            haptics.triggered()
            local.value = local.value.copy(practice = Practice.ACTIVE)
        }
    }

    private fun endPractice() {
        practiceTimer?.cancel()
        practiceTimer = null
        local.value = local.value.copy(practice = Practice.NONE, closed = true)
    }

    private companion object {
        const val PRACTICE_SECOND_MILLIS = 1_000L
    }
}
