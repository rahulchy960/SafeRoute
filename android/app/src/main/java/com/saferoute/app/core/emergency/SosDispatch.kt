// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.di.ApplicationScope
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The alert never waits longer than this for a position before it is sent. */
const val ALERT_POSITION_WAIT_MILLIS = 3_000L

/** A better position that arrives within this time after the alert is sent once more. */
const val LOCATION_UPDATE_WINDOW_MILLIS = 60_000L

/** The fresh look at the contact list may take this long; then the phone's copy is used. */
const val CONTACT_REFRESH_LIMIT_MILLIS = 1_500L

/**
 * May this phone send SOS alerts at all? Only when the user has agreed to the notice that
 * describes them (ADR 0010: no processing before consent). Asked on the phone, without the
 * network: an emergency cannot wait for a server.
 */
interface SosAlertPolicy {
    /** Whether alerts are allowed, and every change of it (for screens). */
    val allowed: Flow<Boolean>

    /** The answer at this moment. */
    suspend fun alertsAllowed(): Boolean = allowed.first()
}

/** What the sender chose for the messages. */
interface SosMessageSettings {
    /** The name contacts see; null when none was set. */
    suspend fun name(): String?

    suspend fun language(): SosLanguage
}

/**
 * A fresh look at the contact list, limited to [CONTACT_REFRESH_LIMIT_MILLIS] by its caller.
 * Returns the ids of the contacts that may still be alerted, or null when the list could not
 * be fetched (no network, a slow server): then nobody is taken off.
 */
fun interface ContactsFreshener {
    suspend fun allowedContactIds(): Set<String>?
}

enum class SmsMode {
    /** This build may send SMS and the user allowed it: the app sends by itself. */
    AUTOMATIC,

    /** Otherwise: the phone's SMS app is opened with the message, and the user presses Send. */
    COMPOSER,
}

fun interface SmsModeSource {
    fun mode(): SmsMode
}

/**
 * Hands a message to the phone's SMS app. Nothing is sent until the user presses Send there.
 * [show] also posts a notification with the same destination, because Android does not let an
 * app open another app's screen from the background (for example while the phone is locked).
 */
interface SmsComposer {
    fun show(phones: List<String>, text: String)

    fun dismiss()
}

/**
 * Tells the user, once, that alerts are still unsent after [ALERT_UNSENT_NOTICE_MILLIS]: a
 * notification with fixed text. Nothing about a contact is in it.
 */
interface UnsentAlertNotifier {
    fun show()

    fun dismiss()
}

/**
 * Moments at which an unsent alert is worth an extra try: the phone left airplane mode, or a
 * network became available. Only signals that need no permission the app does not already
 * have (ADR 0027, note of P014b7). [start] may be called again; [stop] ends the listening.
 */
interface RetrySignals {
    fun start(onSignal: () -> Unit)

    fun stop()
}

/** Two extra tries asked for by signals are at least this far apart. A tap is never held back. */
const val RETRY_SIGNAL_GAP_MILLIS = 10_000L

/** What the active screen says about the alerts. Counts only: never a name or a number. */
sealed interface SosAlertStatus {
    /** Alerts are not switched on for this user: nobody is messaged. */
    data object NotEnabled : SosAlertStatus

    /** Deciding who to alert. */
    data object Preparing : SosAlertStatus

    data object NoContacts : SosAlertStatus

    /**
     * @param unsentForLong alerts are still unsent [ALERT_UNSENT_NOTICE_MILLIS] after the trigger.
     * @param retryingStopped [ALERT_RETRY_LIMIT_MILLIS] passed: what was unsent was given up.
     */
    data class Automatic(
        val summary: SosAlertSummary,
        val unsentForLong: Boolean = false,
        val retryingStopped: Boolean = false,
    ) : SosAlertStatus

    /** The SMS app holds the message for [contacts] contacts; whether it was sent is unknown. */
    data class Composer(val contacts: Int) : SosAlertStatus
}

private val SENDABLE = setOf(SosActionState.PENDING, SosActionState.FAILED_RETRYABLE, SosActionState.IN_PROGRESS)

/**
 * Connects an active emergency to the messages: who is told, with what text, by which way,
 * and what the screen may say about it.
 *
 * It is started AFTER the emergency was written down as triggered, in the app's scope, and
 * nothing here is waited for by the countdown or the trigger: **a message that cannot be
 * sent never delays or undoes an SOS.**
 *
 * The order of an alert:
 * 1. the gate ([SosAlertPolicy]); closed means nobody is messaged, and the screen says so;
 * 2. who: the contacts on the phone, written down once ([SosAlerts.prepare]);
 * 3. at most [ALERT_POSITION_WAIT_MILLIS] for a position; at the same time a fresh look at
 *    the contact list is started and never waited for. If its answer arrives before a
 *    message has left, a contact who opted out is taken off. An opt-out made a moment before
 *    the trigger can therefore be missed: the alert does not wait for a server.
 * 4. send (or open the SMS app), retry while the emergency is active, and send one update if
 *    a better position arrives within [LOCATION_UPDATE_WINDOW_MILLIS].
 *
 * An alert that is not sent is tried again until the emergency ends ([alertRetryRule]): at
 * its own pace, and at once when the user asks ([retryNow]) or a [RetrySignals] signal comes.
 * After [ALERT_UNSENT_NOTICE_MILLIS] the user is told once; after [ALERT_RETRY_LIMIT_MILLIS]
 * trying stops and the screen says so. The timer is this coroutine in the app's scope, which
 * the emergency's foreground service keeps alive: no alarm, no wake lock, no second service.
 *
 * A build that limits its recipients ([SmsRecipientLimit], debug builds) texts only the
 * numbers it allows and marks the others SKIPPED; when it allows none of the contacts, the
 * SMS app is used for all of them, as if the app could not send by itself.
 */
@Singleton
class SosDispatch @Inject constructor(
    private val alerts: SosAlerts,
    private val policy: SosAlertPolicy,
    private val settings: SosMessageSettings,
    private val freshener: ContactsFreshener,
    private val modeSource: SmsModeSource,
    private val composer: SmsComposer,
    private val limit: SmsRecipientLimit,
    private val notifier: UnsentAlertNotifier,
    private val signals: RetrySignals,
    private val trail: SosTrail,
    private val battery: BatteryLevel,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<SosAlertStatus>(SosAlertStatus.NotEnabled)
    val status: StateFlow<SosAlertStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** What an extra try needs to know about the alert that is being sent. Memory only. */
    private class Sending(
        val sosId: String,
        val rule: RetryRule,
        val triggeredAt: Instant,
        val stillActive: suspend () -> Boolean,
        val text: () -> String,
    )

    @Volatile
    private var sending: Sending? = null

    @Volatile
    private var lastSignalTry: Instant? = null

    // What the SMS app was last opened with, for "Open SMS app again". Memory only.
    private var composed: Pair<List<String>, String>? = null

    private fun compose(phones: List<String>, text: String) {
        composed = phones to text
        composer.show(phones, text)
    }

    /** The user tapped "Open SMS app again" on the active screen. */
    fun reopenComposer() {
        composed?.let { (phones, text) -> composer.show(phones, text) }
    }

    /**
     * The emergency [sosId] is active. [resumed] is true when this process found it already
     * active (after the app was closed): then the SMS app is not opened a second time.
     */
    @Synchronized
    fun start(sosId: String, resumed: Boolean, triggeredAt: Instant, stillActive: suspend () -> Boolean) {
        job?.cancel()
        sending = null
        _status.value = SosAlertStatus.Preparing
        job = scope.launch { run(sosId, resumed, triggeredAt, stillActive) }
    }

    /** The emergency is over or was wiped: stop retrying. Sent messages stay sent. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        sending = null
        lastSignalTry = null
        signals.stop()
        notifier.dismiss()
        composed = null
        composer.dismiss()
        _status.value = SosAlertStatus.NotEnabled
    }

    private suspend fun publish(now: Sending) {
        val actions = alerts.actions(now.sosId)
        val summary = summarise(actions)
        _status.value = SosAlertStatus.Automatic(
            summary = summary,
            unsentForLong = summary.waiting > 0 && actions.any { it.type == SosActionType.UNSENT_NOTICE },
            retryingStopped = actions.any { it.type == SosActionType.ALERT && it.lastErrorCategory == FAILED_EXPIRED },
        )
    }

    /**
     * "Send again now" on the active screen, or the emergency screen coming to the front: one
     * try at once for every unsent alert. False when nothing is being sent, the emergency is
     * over, or a try is in flight (then this one is simply not needed).
     */
    suspend fun retryNow(): Boolean {
        val now = sending ?: return false
        if (!now.stillActive()) return false
        val tried = alerts.sendNow(now.sosId, SosActionType.ALERT, now.rule, now.text)
        if (tried && sending === now) publish(now)
        return tried
    }

    /** A signal from the phone: as [retryNow], but not more often than every [RETRY_SIGNAL_GAP_MILLIS]. */
    private fun onRetrySignal() {
        val at = clock.instant()
        val last = lastSignalTry
        if (last != null && at.isBefore(last.plusMillis(RETRY_SIGNAL_GAP_MILLIS))) return
        lastSignalTry = at
        scope.launch { retryNow() }
    }

    /**
     * Waits until [ALERT_UNSENT_NOTICE_MILLIS] after the trigger and, if an alert is still
     * unsent then, tells the user once. The mark is written down first, so a new process
     * that comes by later does not tell them again.
     */
    private suspend fun tellAboutUnsentAlerts(now: Sending) {
        val due = now.triggeredAt.plusMillis(ALERT_UNSENT_NOTICE_MILLIS)
        val wait = Duration.between(clock.instant(), due).toMillis()
        if (wait > 0) delay(wait)
        if (!now.stillActive()) return
        if (alerts.markUnsentNoticeOnce(now.sosId)) notifier.show()
        if (sending === now) publish(now)
    }

    /** The message's facts at this moment: the newest position, the time, the battery. */
    private fun input(name: String?, language: SosLanguage) = SosMessageInput(
        name = name,
        language = language,
        point = trail.state.value.last,
        now = clock.instant(),
        batteryPercent = battery.percent(),
    )

    /**
     * A position counts as good for the alert when it was taken since the countdown began:
     * the trail runs from then on, so such a position is at most a few seconds old.
     */
    private fun hasGoodPosition(triggeredAt: Instant): Boolean =
        trail.state.value.last?.recordedAt?.isBefore(triggeredAt.minus(SOS_COUNTDOWN)) == false

    private suspend fun waitingAlerts(sosId: String): List<SosAction> =
        alerts.actions(sosId).filter { it.type == SosActionType.ALERT && it.state in SENDABLE }

    /**
     * The way of sending for the alerts that are still [waiting]. The SMS app whenever the
     * app cannot send by itself, and also when this build's limit allows none of the people
     * who are waiting: then the user decides in the SMS app, and nothing is sent unseen.
     */
    private fun modeFor(waiting: List<SosAction>): SmsMode = when {
        modeSource.mode() == SmsMode.COMPOSER -> SmsMode.COMPOSER
        limit.limited && waiting.isNotEmpty() && waiting.none { limit.allows(it.phoneE164) } -> SmsMode.COMPOSER
        else -> SmsMode.AUTOMATIC
    }

    private suspend fun run(sosId: String, resumed: Boolean, since: Instant, stillActive: suspend () -> Boolean) {
        if (!policy.alertsAllowed()) {
            _status.value = SosAlertStatus.NotEnabled
            return
        }
        val triggeredAt = clock.instant()
        if (alerts.prepare(sosId, SosActionType.ALERT).isEmpty()) {
            _status.value = SosAlertStatus.NoContacts
            return
        }
        val name = settings.name()
        val language = settings.language()
        if (!resumed) waitBrieflyForPositionAndContacts(sosId, triggeredAt)
        val waiting = waitingAlerts(sosId)
        if (modeFor(waiting) == SmsMode.COMPOSER) {
            _status.value = if (waiting.isEmpty()) SosAlertStatus.NoContacts else SosAlertStatus.Composer(waiting.size)
            if (!resumed && waiting.isNotEmpty()) {
                compose(waiting.map { it.phoneE164 }, buildAlertMessage(input(name, language)))
            }
            return
        }
        // Before the first message leaves: who this build may not text is taken off.
        alerts.skipNotAllowed(sosId, limit)
        // Each try builds its text anew: a retry carries the position and time of its moment.
        // The rule counts from the moment the emergency was triggered ([since]), which is
        // written down: a new process continues the same two-hour and 24-hour clocks.
        val now = Sending(sosId, alertRetryRule(since), since, stillActive) { buildAlertMessage(input(name, language)) }
        sending = now
        suspend fun publish() = publish(now)
        publish()
        signals.start(::onRetrySignal)
        // A child of this run: it ends when the run is stopped.
        val notice = scope.launch(currentCoroutineContext()[Job] ?: Job()) { tellAboutUnsentAlerts(now) }
        // Decided now, before the first message leaves: was the position good enough, or is
        // one update owed if a better one arrives?
        val updateOwed = !resumed && !hasGoodPosition(triggeredAt)
        val update = scope.launch {
            if (updateOwed) sendOneLocationUpdate(sosId, triggeredAt, name, language, stillActive)
        }
        // Found by a new process: the user has just opened the app again, so what is unsent
        // gets a try at once. The number of tries, and with it the wait that follows, goes on
        // from where the old process stopped.
        if (resumed) alerts.sendNow(sosId, SosActionType.ALERT, now.rule, now.text)
        alerts.sendUntilDone(sosId, SosActionType.ALERT, stillActive, onRound = { publish() }, rule = now.rule, text = now.text)
        // Everything is sent or given up: nothing is left to tell the user about or to retry.
        notice.cancel()
        signals.stop()
        if (sending === now) sending = null
        update.join()
        publish()
    }

    /**
     * Both at once. The position is waited for, briefly. The fresh contact list is NOT waited
     * for: the phone never holds an alert back for the server. If the answer comes while the
     * position is awaited, or while a message is still unsent, it is applied then.
     */
    private suspend fun waitBrieflyForPositionAndContacts(sosId: String, triggeredAt: Instant) {
        scope.launch {
            withTimeoutOrNull(CONTACT_REFRESH_LIMIT_MILLIS) { freshener.allowedContactIds() }
                ?.let { allowed -> alerts.dropOptedOut(sosId, allowed) }
        }
        val mode = trail.state.value.mode
        // Without a permission or with location switched off there is nothing to wait for.
        if ((mode == SosTrailMode.PRECISE || mode == SosTrailMode.APPROXIMATE) && !hasGoodPosition(triggeredAt)) {
            withTimeoutOrNull(ALERT_POSITION_WAIT_MILLIS) { trail.state.first { hasGoodPosition(triggeredAt) } }
        }
    }

    /** One follow-up with the first position that is newer than the alert, if it comes in time. */
    private suspend fun sendOneLocationUpdate(
        sosId: String,
        triggeredAt: Instant,
        name: String?,
        language: SosLanguage,
        stillActive: suspend () -> Boolean,
    ) {
        withTimeoutOrNull(LOCATION_UPDATE_WINDOW_MILLIS) { trail.state.first { hasGoodPosition(triggeredAt) } } ?: return
        if (!stillActive()) return
        // Only to contacts whose alert has left; for the others, the retried alert itself
        // carries the new position.
        if (alerts.prepare(sosId, SosActionType.LOCATION_UPDATE).isEmpty()) return
        val text = buildLocationUpdateMessage(input(name, language))
        alerts.sendUntilDone(sosId, SosActionType.LOCATION_UPDATE, stillActive) { text }
    }

    /**
     * "I'm safe" with "Tell my contacts": the follow-up to everyone whose alert was sent, or,
     * in composer mode, the SMS app opened with it for everyone the alert was written for.
     * Called after the emergency was written down as resolved. Returns after the first try;
     * a message that failed is tried again in the background for a while.
     */
    suspend fun tellContactsSafe(sosId: String) {
        if (!policy.alertsAllowed()) return
        val text = buildSafeMessage(settings.name(), settings.language())
        val written = waitingAlerts(sosId)
        if (modeFor(written) == SmsMode.COMPOSER) {
            if (written.isNotEmpty()) compose(written.map { it.phoneE164 }, text)
            return
        }
        if (alerts.prepare(sosId, SosActionType.SAFE).isEmpty()) return
        if (alerts.sendDue(sosId, SosActionType.SAFE) { text } != null) {
            scope.launch { alerts.sendUntilDone(sosId, SosActionType.SAFE, { true }) { text } }
        }
    }
}
