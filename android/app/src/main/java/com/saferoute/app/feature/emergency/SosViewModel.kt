// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.emergency.SOS_COUNTDOWN
import com.saferoute.app.core.emergency.SosEntryPoint
import com.saferoute.app.core.emergency.SosHaptics
import com.saferoute.app.core.emergency.SosLocationReport
import com.saferoute.app.core.emergency.SosRecovery
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.core.emergency.SosTrail
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why the emergency screen was opened. */
enum class SosOpenMode {
    /** A shortcut or a notification: show what is going on, or the emergency options. */
    OPEN,

    /** The hold in the app was completed: start the countdown. */
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

    data class Countdown(val secondsLeft: Int, val practice: Boolean = false) : SosUi

    /**
     * @property recovered true when the app found this emergency after it had been closed:
     *   the screen then says "SOS is still active" first.
     * @property confirmingSafe the "I'm safe" confirmation is open.
     */
    data class Active(
        val location: SosLocationReport,
        val notificationsOff: Boolean,
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

private enum class Practice { NONE, COUNTDOWN, ACTIVE, FINISHED }

private data class Local(
    val asking: Boolean = false,
    val recovered: Boolean = false,
    val confirmingSafe: Boolean = false,
    val closed: Boolean = false,
    val practice: Practice = Practice.NONE,
    val practiceSeconds: Int = 0,
    val practiceConfirming: Boolean = false,
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
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private var practiceTimer: Job? = null
    private var opened = false

    val ui: StateFlow<SosUi> = combine(runner.state, trail.state, local) { run, _, here ->
        when {
            here.closed -> SosUi.Closed
            // A real emergency always wins over a practice run and over the question.
            run is SosRunState.Countdown -> SosUi.Countdown(run.secondsLeft)
            run is SosRunState.Active -> SosUi.Active(
                location = trail.report(),
                notificationsOff = gate.block() != NotificationBlock.None,
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
            else -> SosUi.Options
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SosUi.Options)

    /**
     * Called once when the screen is created for a new reason ([fresh]) and again each time it
     * comes back to the front. A recreated screen (rotation, process restart) never starts a
     * new emergency: it only looks at what is there.
     */
    fun onShown(mode: SosOpenMode, fresh: Boolean) {
        local.value = local.value.copy(refresh = local.value.refresh + 1)
        val first = !opened
        opened = true
        if (first && fresh && mode == SosOpenMode.PRACTICE) {
            startPractice()
            return
        }
        appScope.launch {
            if (first && fresh && mode == SosOpenMode.START) runner.start(SosEntryPoint.IN_APP)
            when (runner.resume()) {
                is SosRecovery.AskSendOrCancel -> local.value = local.value.copy(asking = true)
                is SosRecovery.StillActive -> local.value = local.value.copy(recovered = true)
                is SosRecovery.ResumeCountdown, SosRecovery.Nothing -> Unit
            }
            if (first && fresh && mode == SosOpenMode.SAFE) onSafeClick()
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

    fun onSafeConfirm() {
        if (local.value.practice == Practice.ACTIVE) {
            local.value = local.value.copy(practice = Practice.FINISHED, practiceConfirming = false)
            return
        }
        appScope.launch {
            runner.markSafe()
            local.value = local.value.copy(confirmingSafe = false, closed = true)
        }
    }

    /** Close on the options dialog or on "Practice finished". */
    fun onClose() {
        endPractice()
    }

    private fun startPractice() {
        // Never over a real emergency.
        if (runner.state.value != SosRunState.Idle) return
        practiceTimer?.cancel()
        practiceTimer = viewModelScope.launch {
            for (seconds in SOS_COUNTDOWN.seconds.toInt() downTo 1) {
                local.value = local.value.copy(practice = Practice.COUNTDOWN, practiceSeconds = seconds)
                haptics.countdownTick()
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
