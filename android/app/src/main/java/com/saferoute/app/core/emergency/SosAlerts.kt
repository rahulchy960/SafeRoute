// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.data.ActiveSosContacts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A failed message is tried again this soon, for its first [ALERT_FAST_TRIES] tries. */
const val ALERT_RETRY_MILLIS = 30_000L

/** After this many tries the wait before the next one doubles each time. */
const val ALERT_FAST_TRIES = 10

/** The longest wait between two tries of an alert. */
const val ALERT_RETRY_CAP_MILLIS = 300_000L

/** With an alert still unsent this long after the trigger, the user is told once. */
const val ALERT_UNSENT_NOTICE_MILLIS = 2 * 60 * 60 * 1_000L

/** This long after the trigger an alert that is still unsent is given up. */
const val ALERT_RETRY_LIMIT_MILLIS = 24 * 60 * 60 * 1_000L

/** A follow-up (the "safe" message, the location update) is given up after this many tries. */
const val MAX_FOLLOW_UP_ATTEMPTS = 20

/** The error category of a message given up because [ALERT_RETRY_LIMIT_MILLIS] passed. */
const val FAILED_EXPIRED = "expired"

/**
 * How long to wait after the try number [tries] of an alert: 30 seconds for the first ten
 * tries, then 1, 2 and 4 minutes, then 5 minutes each time. A message that has failed for
 * five minutes is unlikely to succeed in the next thirty seconds, and every try wakes the
 * phone's radio.
 */
fun alertRetryDelayMillis(tries: Int): Long {
    if (tries <= ALERT_FAST_TRIES) return ALERT_RETRY_MILLIS
    val doublings = (tries - ALERT_FAST_TRIES).coerceAtMost(10)
    return (ALERT_RETRY_MILLIS shl doublings).coerceAtMost(ALERT_RETRY_CAP_MILLIS)
}

/**
 * How one kind of message is retried.
 *
 * - An **alert** ([alertRetryRule]): until it is sent or the emergency ends, with a growing
 *   wait, and given up only [ALERT_RETRY_LIMIT_MILLIS] after the trigger (Plan v7 section
 *   7.5: with no signal the device keeps retrying).
 * - A **follow-up** ([FOLLOW_UP_RETRY_RULE]): a fixed wait and at most
 *   [MAX_FOLLOW_UP_ATTEMPTS] tries. It runs beside or after the emergency and must end.
 */
class RetryRule(
    /** Null: no limit on the number of tries. */
    val maxTries: Int?,
    /** From this moment on nothing is tried any more. Null: no such moment. */
    val until: Instant?,
    /** The wait after the given number of tries. */
    val delayAfter: (Int) -> Long,
)

fun alertRetryRule(triggeredAt: Instant): RetryRule =
    RetryRule(maxTries = null, until = triggeredAt.plusMillis(ALERT_RETRY_LIMIT_MILLIS), delayAfter = ::alertRetryDelayMillis)

val FOLLOW_UP_RETRY_RULE = RetryRule(maxTries = MAX_FOLLOW_UP_ATTEMPTS, until = null, delayAfter = { ALERT_RETRY_MILLIS })

enum class SosActionType {
    /** The alert itself. */
    ALERT,

    /** One follow-up when a better position arrived shortly after the alert. */
    LOCATION_UPDATE,

    /** The follow-up after "I'm safe". */
    SAFE,

    /**
     * Not a message: the mark that the user was told, once, that alerts are still unsent
     * after [ALERT_UNSENT_NOTICE_MILLIS]. It has no contact, number or name. Kept with the
     * actions so that it survives a process death and is deleted with the emergency.
     */
    UNSENT_NOTICE,
}

enum class SosActionState {
    PENDING,

    /** Handed to the phone; its answer has not arrived. */
    IN_PROGRESS,
    SENT,
    FAILED_RETRYABLE,
    FAILED_FINAL,

    /**
     * Not sent on purpose: the contact opted out before the message left, or this build may
     * not text that number ([SKIP_DEBUG_NOT_ALLOWLISTED]).
     */
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
data class SosAlertSummary(
    val sent: Int,
    val waiting: Int,
    /** Given up: an error that cannot pass, or [ALERT_RETRY_LIMIT_MILLIS] passed. */
    val failed: Int,
    /** Not sent because the contact opted out. */
    val skipped: Int,
    /** Not sent because this build may not text that number (a debug build's list of test numbers). */
    val held: Int = 0,
) {
    val total: Int get() = sent + waiting + failed + skipped + held
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
        skipped = ofType.count { it.state == SosActionState.SKIPPED && it.lastErrorCategory != SKIP_DEBUG_NOT_ALLOWLISTED },
        held = ofType.count { it.state == SosActionState.SKIPPED && it.lastErrorCategory == SKIP_DEBUG_NOT_ALLOWLISTED },
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
 * - **An alert is retried for as long as the emergency is active** ([alertRetryRule]). When
 *   each message is due again follows from its stored number of tries and the time of the
 *   last one, so a new process continues the same schedule.
 * - **A final failure stays final**: an error that cannot pass is never tried again.
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
            // Not a message to anyone: written by markUnsentNoticeOnce only.
            SosActionType.UNSENT_NOTICE -> emptyList()
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
     * Every message that has not left yet and whose number [limit] does not allow is marked
     * SKIPPED with [SKIP_DEBUG_NOT_ALLOWLISTED]. Returns how many. Does nothing in a build
     * without a limit.
     */
    suspend fun skipNotAllowed(sosId: String, limit: SmsRecipientLimit): Int = sending.withLock {
        if (!limit.limited) return@withLock 0
        store.actions(sosId)
            .filter { it.state in UNFINISHED && !limit.allows(it.phoneE164) }
            .count {
                store.moveIf(it.id, UNFINISHED, SosActionState.SKIPPED, it.attemptCount, SKIP_DEBUG_NOT_ALLOWLISTED, clock.instant())
            }
    }

    /**
     * One round: every message of [type] that has not been sent and is **due** is tried once.
     * A message that failed is due again when its wait is over ([RetryRule.delayAfter]); one
     * that was never tried, or was in progress when the process died, is due at once. [text]
     * is asked for each try, so a retry carries the position and the time of that moment.
     *
     * @return the milliseconds until something is due again, or null when nothing is left
     *   that another round could send.
     */
    suspend fun sendDue(
        sosId: String,
        type: SosActionType,
        rule: RetryRule = FOLLOW_UP_RETRY_RULE,
        text: () -> String,
    ): Long? = sending.withLock { round(sosId, type, rule, force = false, text) }

    /**
     * A try **now** for every unsent message of [type], whatever its wait ("Send again now",
     * the app coming to the front, the network coming back). Does nothing and returns false
     * while a round is in flight: the same message is never handed to the phone twice.
     */
    suspend fun sendNow(sosId: String, type: SosActionType, rule: RetryRule, text: () -> String): Boolean {
        if (!sending.tryLock()) return false
        try {
            round(sosId, type, rule, force = true, text)
        } finally {
            sending.unlock()
        }
        return true
    }

    private suspend fun round(sosId: String, type: SosActionType, rule: RetryRule, force: Boolean, text: () -> String): Long? {
        var next: Long? = null
        fun dueIn(millis: Long) {
            next = minOf(next ?: millis, millis)
        }
        for (action in store.actions(sosId).filter { it.type == type && it.state in UNFINISHED }) {
            val now = clock.instant()
            if (rule.until != null && !now.isBefore(rule.until)) {
                store.moveIf(action.id, UNFINISHED, SosActionState.FAILED_FINAL, action.attemptCount, FAILED_EXPIRED, now)
                continue
            }
            if (rule.maxTries != null && action.attemptCount >= rule.maxTries) {
                store.moveIf(action.id, UNFINISHED, SosActionState.FAILED_FINAL, action.attemptCount, "attempts", now)
                continue
            }
            if (!force && action.state == SosActionState.FAILED_RETRYABLE) {
                val dueAt = action.updatedAt.plusMillis(rule.delayAfter(action.attemptCount))
                val wait = Duration.between(now, dueAt).toMillis()
                if (wait > 0) {
                    dueIn(wait)
                    continue
                }
            }
            val attempt = action.attemptCount + 1
            // Written before the phone is asked: a second sender cannot take the same message.
            if (!store.moveIf(action.id, UNFINISHED, SosActionState.IN_PROGRESS, attempt, null, now)) continue
            val (state, error) = when (val outcome = gateway.send(action.phoneE164, text())) {
                SmsOutcome.Sent -> SosActionState.SENT to null
                is SmsOutcome.Final -> SosActionState.FAILED_FINAL to outcome.category
                is SmsOutcome.Retryable ->
                    if (rule.maxTries != null && attempt >= rule.maxTries) {
                        SosActionState.FAILED_FINAL to outcome.category
                    } else {
                        SosActionState.FAILED_RETRYABLE to outcome.category
                    }
            }
            store.moveIf(action.id, setOf(SosActionState.IN_PROGRESS), state, attempt, error, clock.instant())
            if (state == SosActionState.FAILED_RETRYABLE) dueIn(rule.delayAfter(attempt))
        }
        // Wake up at the moment the rule ends, so that what is left is given up on time.
        val wait = next ?: return null
        val untilEnd = rule.until?.let { Duration.between(clock.instant(), it).toMillis().coerceAtLeast(1) }
        return if (untilEnd != null) minOf(wait, untilEnd) else wait
    }

    /**
     * Rounds until everything is sent or given up, or until [stillWanted] says no (the
     * emergency ended), each after the wait the [rule] asks for. [onRound] is called after
     * each round, for a screen that shows the counts.
     */
    suspend fun sendUntilDone(
        sosId: String,
        type: SosActionType,
        stillWanted: suspend () -> Boolean,
        onRound: suspend () -> Unit = {},
        rule: RetryRule = FOLLOW_UP_RETRY_RULE,
        text: () -> String,
    ) {
        while (stillWanted()) {
            val wait = sendDue(sosId, type, rule, text)
            onRound()
            if (wait == null) return
            delay(wait)
        }
    }

    /**
     * Writes down, once per emergency, that the user was told about unsent alerts. True only
     * the first time and only while an alert is really still unsent: the caller then shows
     * the notification. A second call, also from a new process, returns false.
     */
    suspend fun markUnsentNoticeOnce(sosId: String): Boolean = sending.withLock {
        val all = store.actions(sosId)
        if (all.any { it.type == SosActionType.UNSENT_NOTICE }) return@withLock false
        if (all.none { it.type == SosActionType.ALERT && it.state in UNFINISHED }) return@withLock false
        store.insertAll(
            listOf(
                SosAction(
                    id = ids.next(),
                    clientSosId = sosId,
                    contactId = "",
                    phoneE164 = "",
                    name = "",
                    type = SosActionType.UNSENT_NOTICE,
                    state = SosActionState.SENT,
                    attemptCount = 0,
                    lastErrorCategory = null,
                    updatedAt = clock.instant(),
                ),
            ),
        )
        true
    }
}
