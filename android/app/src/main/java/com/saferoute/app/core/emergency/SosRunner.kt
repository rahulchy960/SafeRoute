// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.di.ApplicationScope
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What is running in this process right now. The database says what SHOULD be running. */
sealed interface SosRunState {
    data object Idle : SosRunState

    /** [secondsLeft] counts 5, 4, 3, 2, 1. */
    data class Countdown(val record: SosRecord, val secondsLeft: Int) : SosRunState

    data class Active(val record: SosRecord) : SosRunState
}

/**
 * Keeps the process alive and visible while an emergency runs: on a phone, the foreground
 * service and its notification. [begin] and [end] may be called more than once.
 */
interface SosHost {
    fun begin()

    fun end()
}

/** What the user feels. Vibration only: a loud phone can put its owner at risk. */
interface SosHaptics {
    /** Once per second of the countdown. */
    fun countdownTick()

    /** The alert went out. */
    fun triggered()
}

private const val MILLIS_PER_SECOND = 1_000L
private const val RECHECK_MILLIS = 250L

/**
 * Runs an emergency in this process: the countdown, the trigger at its end, the location
 * trail, and the end. It belongs to the app, **not to a screen**: a screen is destroyed by a
 * rotation, by the lock screen and by leaving the app, and the countdown must go on.
 *
 * It never decides anything by itself. Every step asks [SosEngine], which writes the state
 * first and answers once; the vibration, the trail and the host follow a "yes". The timer is
 * rebuilt from the stored `countdownEndsAt`, so a new process continues the same countdown
 * instead of starting another five seconds.
 *
 * It reads no contact and sends nothing (messages arrive with P014b, at [onTriggered]).
 */
@Singleton
class SosRunner @Inject constructor(
    private val engine: SosEngine,
    private val trail: SosTrail,
    private val host: SosHost,
    private val haptics: SosHaptics,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SosRunState>(SosRunState.Idle)
    val state: StateFlow<SosRunState> = _state.asStateFlow()

    private val steps = Mutex()
    private var timer: Job? = null

    /**
     * Starts the countdown. If an emergency is already going on, nothing new starts and the
     * existing one is returned (and picked up again if this process was not running it).
     */
    suspend fun start(entryPoint: SosEntryPoint): SosStart {
        val result = steps.withLock {
            engine.startCountdown(entryPoint).also { if (it is SosStart.Started) runCountdown(it.record) }
        }
        if (result is SosStart.AlreadyActive) resume()
        return result
    }

    /**
     * Picks up an emergency that the database knows and this process is not running (app
     * start, resume, a notification tap). A countdown with time left and an active emergency
     * continue at once; a countdown past its end is only reported, for the question "Send now
     * or Cancel" ([sendNow], [cancel]).
     */
    suspend fun resume(): SosRecovery = steps.withLock {
        if (_state.value != SosRunState.Idle) return@withLock SosRecovery.Nothing
        val recovery = engine.recovery()
        when (recovery) {
            is SosRecovery.ResumeCountdown -> runCountdown(recovery.record)
            is SosRecovery.StillActive -> runActive(recovery.record)
            is SosRecovery.AskSendOrCancel, SosRecovery.Nothing -> Unit
        }
        recovery
    }

    /** Cancels before the alert went out. False when there was nothing left to cancel. */
    suspend fun cancel(): Boolean = steps.withLock {
        if (!engine.cancel()) return@withLock false
        stopEverything()
        true
    }

    /** The user's "Send now" for a countdown whose end passed while nothing was running. */
    suspend fun sendNow(): Boolean = steps.withLock {
        // Only for a countdown nobody is running; a running one ends by itself or by cancel.
        if (_state.value != SosRunState.Idle) return@withLock false
        val record = engine.trigger(force = true) ?: return@withLock false
        onTriggered(record)
        true
    }

    /** "I'm safe". False when no alert had gone out. */
    suspend fun markSafe(): Boolean = steps.withLock {
        if (!engine.resolve()) return@withLock false
        stopEverything()
        true
    }

    private fun runCountdown(record: SosRecord) {
        // The state first: the host looks at it to decide what its notification says.
        _state.value = SosRunState.Countdown(record, secondsLeft(record) ?: 1)
        host.begin()
        // From the first second, so that a position is ready when the alert goes out.
        trail.start(record.clientSosId)
        timer?.cancel()
        timer = scope.launch { countDown(record) }
    }

    private fun leftMillis(record: SosRecord): Long =
        Duration.between(clock.instant(), record.countdownEndsAt).toMillis()

    /** Whole seconds left, rounded up; null when the end has come. */
    private fun secondsLeft(record: SosRecord): Int? =
        leftMillis(record).takeIf { it > 0 }?.let { ((it + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt() }

    private suspend fun countDown(record: SosRecord) {
        var shown = 0
        while (true) {
            val leftMillis = leftMillis(record)
            val seconds = secondsLeft(record)
            if (seconds != null) {
                if (seconds != shown) {
                    shown = seconds
                    _state.value = SosRunState.Countdown(record, seconds)
                    haptics.countdownTick()
                }
                // Until the next whole second.
                delay(leftMillis - (seconds - 1) * MILLIS_PER_SECOND)
                continue
            }
            val done = steps.withLock {
                val triggered = engine.trigger()
                if (triggered != null) onTriggered(triggered)
                // Null and no countdown left: it was cancelled, or someone else triggered it.
                triggered != null || engine.current()?.state != SosState.COUNTDOWN
            }
            if (done) return
            // Still a countdown but "not due": the phone's clock moved. Look again shortly.
            delay(RECHECK_MILLIS)
        }
    }

    /** The state is TRIGGERED_LOCAL on disk. Now the phone acts. */
    private suspend fun onTriggered(record: SosRecord) {
        haptics.triggered()
        runActive(record)
    }

    private suspend fun runActive(record: SosRecord) {
        if (record.state == SosState.TRIGGERED_LOCAL) engine.markActive()
        _state.value = SosRunState.Active(engine.current() ?: record)
        host.begin()
        trail.start(record.clientSosId)
    }

    private fun stopEverything() {
        timer?.cancel()
        timer = null
        trail.stop()
        host.end()
        _state.value = SosRunState.Idle
    }
}
