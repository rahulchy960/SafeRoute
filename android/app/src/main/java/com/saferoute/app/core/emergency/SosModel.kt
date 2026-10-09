// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.time.Duration
import java.time.Instant

/** How long the user can still cancel before the alert goes out (Plan v7 section 7.1). */
val SOS_COUNTDOWN: Duration = Duration.ofSeconds(5)

/** Emergency records and their location trail are deleted from the phone after this long. */
val SOS_RETENTION: Duration = Duration.ofDays(30)

/**
 * Where an emergency stands on this phone (Plan v7 section 7.2).
 *
 * [IDLE] means "no record": it is never written. [ARMING] is the finger holding the button
 * and lives in memory only: a hold that the process did not survive is a hold that was
 * released. Every state from [COUNTDOWN] on is in the database BEFORE anything is done about
 * it, so a process that dies can pick it up again. [SYNCING] is unused until the server part
 * (P015).
 */
enum class SosState { IDLE, ARMING, COUNTDOWN, TRIGGERED_LOCAL, SYNCING, ACTIVE, RESOLVED }

/** What the user did to start it. Stored with the record. */
enum class SosEntryPoint { IN_APP, TILE, NOTIFICATION, WIDGET }

/** Whether the server knows about the record. Nothing is sent before P015. */
enum class SosSyncState { NOT_SYNCED }

/** Things that happen to an emergency. See [nextSosState] for which are allowed when. */
enum class SosEvent {
    /** The finger went down on "Hold to send". */
    HOLD_STARTED,

    /** The finger came up before the hold was complete. */
    HOLD_RELEASED,

    /** The hold was completed, or a shortcut outside the app was used. */
    COUNTDOWN_STARTED,

    /** The user cancelled before the alert went out. */
    CANCELLED,

    /** The countdown reached its end. */
    COUNTDOWN_ELAPSED,

    /** The phone began its own actions (messages, location trail). */
    LOCAL_ACTIONS_STARTED,

    /** The user confirmed "I'm safe". */
    MARKED_SAFE,
}

/**
 * The whole state machine: the state after [event], or null when [event] is not allowed in
 * [state] (the caller then changes nothing).
 *
 * Two rules worth seeing here: an emergency can be cancelled only BEFORE the alert went out
 * (afterwards the way out is "I'm safe", which tells the contacts), and nothing leaves
 * [SosState.RESOLVED].
 */
fun nextSosState(state: SosState, event: SosEvent): SosState? = when (state) {
    SosState.IDLE -> when (event) {
        SosEvent.HOLD_STARTED -> SosState.ARMING
        SosEvent.COUNTDOWN_STARTED -> SosState.COUNTDOWN
        else -> null
    }

    SosState.ARMING -> when (event) {
        SosEvent.HOLD_RELEASED, SosEvent.CANCELLED -> SosState.IDLE
        SosEvent.COUNTDOWN_STARTED -> SosState.COUNTDOWN
        else -> null
    }

    SosState.COUNTDOWN -> when (event) {
        SosEvent.CANCELLED -> SosState.IDLE
        SosEvent.COUNTDOWN_ELAPSED -> SosState.TRIGGERED_LOCAL
        else -> null
    }

    SosState.TRIGGERED_LOCAL -> when (event) {
        SosEvent.LOCAL_ACTIONS_STARTED -> SosState.ACTIVE
        SosEvent.MARKED_SAFE -> SosState.RESOLVED
        else -> null
    }

    SosState.SYNCING, SosState.ACTIVE -> when (event) {
        SosEvent.MARKED_SAFE -> SosState.RESOLVED
        else -> null
    }

    SosState.RESOLVED -> null
}

/** One emergency as the phone remembers it. Holds no name, number or place. */
data class SosRecord(
    /** A UUID version 7 made on the phone; later the idempotency key for the server. */
    val clientSosId: String,
    val state: SosState,
    val entryPoint: SosEntryPoint,
    /** Always false today: a practice run is never written down. Kept for the data model. */
    val practice: Boolean,
    val startedAt: Instant,
    /** The moment the alert goes out unless cancelled. The timer is rebuilt from this. */
    val countdownEndsAt: Instant,
    val triggeredAt: Instant? = null,
    val resolvedAt: Instant? = null,
    val syncState: SosSyncState = SosSyncState.NOT_SYNCED,
)

/** One position of the trail kept during an emergency. */
data class SosPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val recordedAt: Instant,
    /** True when Android says the position came from a mock provider. */
    val mock: Boolean,
) {
    // The generated toString() would print where the user is.
    override fun toString(): String = "SosPoint(hidden)"
}

/**
 * Where emergencies are kept on the phone. The real one is a Room database (`core/data`);
 * nothing here touches the network.
 *
 * The "if" functions are what makes a trigger happen once: each changes the row only when it
 * is still in the expected state and says whether it did.
 */
interface SosStore {

    /** The emergency that is not resolved yet, or null. There is at most one. */
    suspend fun unresolved(): SosRecord?

    /** Stores [record] unless an unresolved one exists; returns the one that is stored. */
    suspend fun insertIfNoneUnresolved(record: SosRecord): SosRecord

    /** Moves the record [from] → [to]; false when it was not in [from] (or is gone). */
    suspend fun advanceIf(id: String, from: SosState, to: SosState, at: Instant): Boolean

    /** Deletes the record and everything that hangs on it, only while it is in [state]. */
    suspend fun deleteIf(id: String, state: SosState): Boolean

    suspend fun addPoint(id: String, point: SosPoint)

    /** Oldest first. */
    suspend fun points(id: String): List<SosPoint>

    /** Deletes records started before [cutoff] and points recorded before it. */
    suspend fun purgeBefore(cutoff: Instant)

    /** Deletes everything (sign-out, blocked account). */
    suspend fun wipe()
}
