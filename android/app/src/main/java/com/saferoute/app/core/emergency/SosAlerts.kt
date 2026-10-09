// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.data.ActiveSosContacts
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A failed message is tried again this often while the emergency is active. */
const val ALERT_RETRY_MILLIS = 30_000L

/** After this many tries a message is given up (about ten minutes). */
const val MAX_ALERT_ATTEMPTS = 20

enum class SosActionType {
    /** The alert itself. */
    ALERT,

    /** One follow-up when a better position arrived shortly after the alert. */
    LOCATION_UPDATE,

    /** The follow-up after "I'm safe". */
    SAFE,
}

enum class SosActionState {
    PENDING,

    /** Handed to the phone; its answer has not arrived. */
    IN_PROGRESS,
    SENT,
    FAILED_RETRYABLE,
    FAILED_FINAL,

    /** Not sent on purpose: the contact opted out before the message left. */
    SKIPPED,
}

/**
 * One message to one contact, as the phone remembers it. The name and number are copies taken
 * when the emergency began. PERSONAL DATA of a person who is not a user: never printed.
 */
data class SosAction(
    val id: String,
    val clientSosId: String,
    val contactId: String,
    val phoneE164: String,
    val name: String,
    val type: SosActionType,
    val state: SosActionState,
    val attemptCount: Int,
    /** A short word such as `no_service`. Never a number, a name or a text. */
    val lastErrorCategory: String?,
    val updatedAt: Instant,
) {
    override fun toString(): String = "SosAction(hidden)"
}

/** Where the actions are kept (the `sos_actions` table). */
interface SosActionStore {
    /** Oldest first. */
    suspend fun actions(sosId: String): List<SosAction>

    suspend fun insertAll(actions: List<SosAction>)

    /** Changes the action only while it is in one of [from]; says whether it did. */
    suspend fun moveIf(
        id: String,
        from: Set<SosActionState>,
        to: SosActionState,
        attemptCount: Int,
        errorCategory: String?,
        at: Instant,
    ): Boolean
}

/** What the phone answered for one message. */
sealed interface SmsOutcome {
    data object Sent : SmsOutcome

    /** Worth another try: no signal, radio off, the network was busy. */
    data class Retryable(val category: String) : SmsOutcome

    /** Trying again cannot help: no permission, a number the network refuses. */
    data class Final(val category: String) : SmsOutcome
}

/** Sends one text to one number from this phone's SIM. Tests use a fake. */
fun interface SmsGateway {
    suspend fun send(phoneE164: String, text: String): SmsOutcome
}

/** Counts for a screen, also for the lock screen: never a name or a number. */
data class SosAlertSummary(val sent: Int, val waiting: Int, val failed: Int, val skipped: Int) {
    val total: Int get() = sent + waiting + failed + skipped
}

fun summarise(actions: List<SosAction>, type: SosActionType = SosActionType.ALERT): SosAlertSummary {
    val ofType = actions.filter { it.type == type }
    return SosAlertSummary(
        sent = ofType.count { it.state == SosActionState.SENT },
        waiting = ofType.count {
            it.state == SosActionState.PENDING ||
                it.state == SosActionState.IN_PROGRESS ||
                it.state == SosActionState.FAILED_RETRYABLE
        },
        failed = ofType.count { it.state == SosActionState.FAILED_FINAL },
        skipped = ofType.count { it.state == SosActionState.SKIPPED },
    )
}

private val UNFINISHED = setOf(SosActionState.PENDING, SosActionState.FAILED_RETRYABLE, SosActionState.IN_PROGRESS)

/**
 * The messages of an emergency: who gets one, what was sent, what is tried again.
 *
 * The same rule as everywhere in the SOS flow: **write first, act second**. A message is
 * marked IN_PROGRESS before the phone is asked to send it, and SENT only when the phone said
 * so. From that follow the promises:
 *
 * - **A SENT message is never sent again**, whatever is retried around it.
 * - **Who is told is decided once**, when the emergency begins ([prepare]): later changes of
 *   the contact list do not add anyone. A contact who opted out in the meantime is taken off
 *   ([dropOptedOut]) and marked SKIPPED.
 * - **An opted-out contact is never in the list at all**: [ActiveSosContacts] leaves them out.
 * - A message that was IN_PROGRESS when the process died is tried again. The phone may have
 *   sent it already, so in that one case a contact can get the alert twice: in an emergency
 *   a second alert is the smaller harm than none.
 *
 * It reads the contacts from the phone only and never touches the network. Nothing here logs.
 */
@Singleton
class SosAlerts @Inject constructor(
    private val contacts: ActiveSosContacts,
    private val store: SosActionStore,
    private val gateway: SmsGateway,
    private val clock: Clock,
    private val ids: UuidV7Generator,
) {
    private val sending = Mutex()

    suspend fun actions(sosId: String): List<SosAction> = store.actions(sosId)

    /**
     * Decides who gets a message of [type] and writes one PENDING action each. Calling it
     * again changes nothing and returns what is there.
     *
     * An ALERT goes to the contacts the phone knows now. A follow-up goes only to the contacts
     * whose alert was really sent: nobody is told "they are safe" who was never told anything.
     */
    suspend fun prepare(sosId: String, type: SosActionType): List<SosAction> = sending.withLock {
        val all = store.actions(sosId)
        val existing = all.filter { it.type == type }
        if (existing.isNotEmpty()) return@withLock existing
        val now = clock.instant()
        val created = when (type) {
            SosActionType.ALERT -> contacts.current().map { Triple(it.id, it.phoneE164, it.name) }
            SosActionType.LOCATION_UPDATE, SosActionType.SAFE ->
                all.filter { it.type == SosActionType.ALERT && it.state == SosActionState.SENT }
                    .map { Triple(it.contactId, it.phoneE164, it.name) }
        }.map { (contactId, phone, name) ->
            SosAction(
                id = ids.next(),
                clientSosId = sosId,
                contactId = contactId,
                phoneE164 = phone,
                name = name,
                type = type,
                state = SosActionState.PENDING,
                attemptCount = 0,
                lastErrorCategory = null,
                updatedAt = now,
            )
        }
        store.insertAll(created)
        created
    }

    /**
     * After a fresh look at the contact list: every message that has not left yet and whose
     * contact is no longer in [stillAllowed] is marked SKIPPED. Returns how many.
     */
    suspend fun dropOptedOut(sosId: String, stillAllowed: Set<String>): Int = sending.withLock {
        store.actions(sosId)
            .filter { it.state in UNFINISHED && it.contactId !in stillAllowed }
            .count { store.moveIf(it.id, UNFINISHED, SosActionState.SKIPPED, it.attemptCount, "opted_out", clock.instant()) }
    }

    /**
     * One round: every message of [type] that has not been sent is tried once. [text] is asked
     * for each try, so a retry carries the position and the time of that moment.
     *
     * @return true when something is left that another round could still send.
     */
    suspend fun sendDue(sosId: String, type: SosActionType, text: () -> String): Boolean = sending.withLock {
        var retryLater = false
        for (action in store.actions(sosId).filter { it.type == type && it.state in UNFINISHED }) {
            val now = clock.instant()
            if (action.attemptCount >= MAX_ALERT_ATTEMPTS) {
                store.moveIf(action.id, UNFINISHED, SosActionState.FAILED_FINAL, action.attemptCount, "attempts", now)
                continue
            }
            val attempt = action.attemptCount + 1
            // Written before the phone is asked: a second sender cannot take the same message.
            if (!store.moveIf(action.id, UNFINISHED, SosActionState.IN_PROGRESS, attempt, null, now)) continue
            val (state, error) = when (val outcome = gateway.send(action.phoneE164, text())) {
                SmsOutcome.Sent -> SosActionState.SENT to null
                is SmsOutcome.Final -> SosActionState.FAILED_FINAL to outcome.category
                is SmsOutcome.Retryable ->
                    if (attempt >= MAX_ALERT_ATTEMPTS) {
                        SosActionState.FAILED_FINAL to outcome.category
                    } else {
                        SosActionState.FAILED_RETRYABLE to outcome.category
                    }
            }
            store.moveIf(action.id, setOf(SosActionState.IN_PROGRESS), state, attempt, error, clock.instant())
            if (state == SosActionState.FAILED_RETRYABLE) retryLater = true
        }
        retryLater
    }

    /**
     * Rounds every [ALERT_RETRY_MILLIS] until everything is sent or given up, or until
     * [stillWanted] says no (the emergency ended).
     */
    suspend fun sendUntilDone(
        sosId: String,
        type: SosActionType,
        stillWanted: suspend () -> Boolean,
        text: () -> String,
    ) {
        while (stillWanted()) {
            if (!sendDue(sosId, type, text)) return
            delay(ALERT_RETRY_MILLIS)
        }
    }
}
