// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The answer to "start an emergency". */
sealed interface SosStart {
    val record: SosRecord

    /** A new countdown began. */
    data class Started(override val record: SosRecord) : SosStart

    /** One was already going on; show it instead of starting a second. */
    data class AlreadyActive(override val record: SosRecord) : SosStart
}

/** What to show when the app finds an emergency it is not currently running. */
sealed interface SosRecovery {

    data object Nothing : SosRecovery

    /** The countdown has time left: show it again with the [remaining] time. */
    data class ResumeCountdown(val record: SosRecord, val remaining: Duration) : SosRecovery

    /**
     * The countdown's end passed while nothing was running it. Ask "Send now or Cancel":
     * the app never sends late by itself and never drops it silently.
     */
    data class AskSendOrCancel(val record: SosRecord) : SosRecovery

    /** The alert went out and the user has not said they are safe. */
    data class StillActive(val record: SosRecord) : SosRecovery
}

/**
 * What to do about [record] at time [now] when no timer for it is running in this process
 * (app start, resume, a tap on a notification, after a restart of the phone).
 *
 * A countdown whose start lies in the future means the phone's clock was changed; that one is
 * asked about too, because the remaining time can no longer be trusted.
 */
fun recoveryFor(record: SosRecord?, now: Instant): SosRecovery = when (record?.state) {
    null, SosState.IDLE, SosState.ARMING, SosState.RESOLVED -> SosRecovery.Nothing

    SosState.COUNTDOWN -> when {
        now.isBefore(record.startedAt) || !now.isBefore(record.countdownEndsAt) ->
            SosRecovery.AskSendOrCancel(record)

        else -> SosRecovery.ResumeCountdown(record, Duration.between(now, record.countdownEndsAt))
    }

    SosState.TRIGGERED_LOCAL, SosState.SYNCING, SosState.ACTIVE -> SosRecovery.StillActive(record)
}

/**
 * The one place that moves an emergency from state to state. Every function writes the new
 * state FIRST and returns only then; the caller does the side effect (vibrate, send, start the
 * location trail) after it returned "yes". So a process that dies between the two finds the
 * state on disk and continues, and two callers racing for the same step get one "yes".
 *
 * It starts no service, sends nothing and reads no contact: it is the bookkeeping.
 */
@Singleton
class SosEngine @Inject constructor(
    private val store: SosStore,
    private val clock: Clock,
    private val ids: UuidV7Generator,
) {
    // One step at a time inside this process; the store's "if" functions guard the rest.
    private val steps = Mutex()

    /** The emergency that is going on, or null. */
    suspend fun current(): SosRecord? = store.unresolved()

    suspend fun recovery(): SosRecovery = recoveryFor(store.unresolved(), clock.instant())

    /**
     * Begins the countdown, unless an emergency is already going on: a second tap, tile or
     * widget never makes a second one.
     */
    suspend fun startCountdown(entryPoint: SosEntryPoint): SosStart = steps.withLock {
        val now = clock.instant()
        val candidate = SosRecord(
            clientSosId = ids.next(),
            state = SosState.COUNTDOWN,
            entryPoint = entryPoint,
            practice = false,
            startedAt = now,
            countdownEndsAt = now.plus(SOS_COUNTDOWN),
        )
        val stored = store.insertIfNoneUnresolved(candidate)
        if (stored.clientSosId == candidate.clientSosId) SosStart.Started(stored) else SosStart.AlreadyActive(stored)
    }

    /**
     * Cancels before the alert went out: the record is deleted and nothing remains. False when
     * there is nothing to cancel or the alert already went out (then only "I'm safe" ends it).
     */
    suspend fun cancel(): Boolean = steps.withLock {
        val record = store.unresolved() ?: return@withLock false
        nextSosState(record.state, SosEvent.CANCELLED) ?: return@withLock false
        store.deleteIf(record.clientSosId, record.state)
    }

    /**
     * The countdown reached its end. Returns the triggered record exactly once; null when it
     * is not due yet, was cancelled, or was already triggered. [force] is the user's "Send
     * now" on the recovery question, which is the only way a late countdown is sent.
     */
    suspend fun trigger(force: Boolean = false): SosRecord? = steps.withLock {
        val record = store.unresolved() ?: return@withLock null
        val to = nextSosState(record.state, SosEvent.COUNTDOWN_ELAPSED) ?: return@withLock null
        val now = clock.instant()
        if (!force && now.isBefore(record.countdownEndsAt)) return@withLock null
        if (!store.advanceIf(record.clientSosId, record.state, to, now)) return@withLock null
        record.copy(state = to, triggeredAt = now)
    }

    /** The phone began its own actions. False when the record is not waiting for that. */
    suspend fun markActive(): Boolean = advance(SosEvent.LOCAL_ACTIONS_STARTED)

    /** "I'm safe". False when no alert had gone out. */
    suspend fun resolve(): Boolean = advance(SosEvent.MARKED_SAFE)

    private suspend fun advance(event: SosEvent): Boolean = steps.withLock {
        val record = store.unresolved() ?: return@withLock false
        val to = nextSosState(record.state, event) ?: return@withLock false
        store.advanceIf(record.clientSosId, record.state, to, clock.instant())
    }
}
