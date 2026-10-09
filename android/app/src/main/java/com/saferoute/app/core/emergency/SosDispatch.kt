// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.di.ApplicationScope
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
fun interface SosAlertPolicy {
    suspend fun alertsAllowed(): Boolean
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

/** What the active screen says about the alerts. Counts only: never a name or a number. */
sealed interface SosAlertStatus {
    /** Alerts are not switched on for this user: nobody is messaged. */
    data object NotEnabled : SosAlertStatus

    /** Deciding who to alert. */
    data object Preparing : SosAlertStatus

    data object NoContacts : SosAlertStatus

    data class Automatic(val summary: SosAlertSummary) : SosAlertStatus

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
 */
@Singleton
class SosDispatch @Inject constructor(
    private val alerts: SosAlerts,
    private val policy: SosAlertPolicy,
    private val settings: SosMessageSettings,
    private val freshener: ContactsFreshener,
    private val modeSource: SmsModeSource,
    private val composer: SmsComposer,
    private val trail: SosTrail,
    private val battery: BatteryLevel,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<SosAlertStatus>(SosAlertStatus.NotEnabled)
    val status: StateFlow<SosAlertStatus> = _status.asStateFlow()

    private var job: Job? = null

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
    fun start(sosId: String, resumed: Boolean, stillActive: suspend () -> Boolean) {
        job?.cancel()
        _status.value = SosAlertStatus.Preparing
        job = scope.launch { run(sosId, resumed, stillActive) }
    }

    /** The emergency is over or was wiped: stop retrying. Sent messages stay sent. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        composed = null
        composer.dismiss()
        _status.value = SosAlertStatus.NotEnabled
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

    private suspend fun run(sosId: String, resumed: Boolean, stillActive: suspend () -> Boolean) {
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
        if (modeSource.mode() == SmsMode.COMPOSER) {
            val waiting = alerts.actions(sosId).filter { it.type == SosActionType.ALERT && it.state in SENDABLE }
            _status.value = if (waiting.isEmpty()) SosAlertStatus.NoContacts else SosAlertStatus.Composer(waiting.size)
            if (!resumed && waiting.isNotEmpty()) {
                compose(waiting.map { it.phoneE164 }, buildAlertMessage(input(name, language)))
            }
            return
        }
        suspend fun publish() {
            _status.value = SosAlertStatus.Automatic(summarise(alerts.actions(sosId)))
        }
        publish()
        // Decided now, before the first message leaves: was the position good enough, or is
        // one update owed if a better one arrives?
        val updateOwed = !resumed && !hasGoodPosition(triggeredAt)
        val update = scope.launch {
            if (updateOwed) sendOneLocationUpdate(sosId, triggeredAt, name, language, stillActive)
        }
        // Each try builds its text anew: a retry carries the position and time of its moment.
        alerts.sendUntilDone(sosId, SosActionType.ALERT, stillActive, onRound = { publish() }) {
            buildAlertMessage(input(name, language))
        }
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
        if (modeSource.mode() == SmsMode.COMPOSER) {
            val written = alerts.actions(sosId).filter { it.type == SosActionType.ALERT && it.state in SENDABLE }
            if (written.isNotEmpty()) compose(written.map { it.phoneE164 }, text)
            return
        }
        if (alerts.prepare(sosId, SosActionType.SAFE).isEmpty()) return
        if (alerts.sendDue(sosId, SosActionType.SAFE) { text }) {
            scope.launch { alerts.sendUntilDone(sosId, SosActionType.SAFE, { true }) { text } }
        }
    }
}
