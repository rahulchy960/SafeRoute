// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics

import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.diagnostics.DiagnosticEvent
import com.saferoute.app.core.diagnostics.DiagnosticsLog
import com.saferoute.app.core.diagnostics.DiagnosticsReporter
import com.saferoute.app.core.diagnostics.SmsFailure
import com.saferoute.app.core.emergency.SmsGateway
import com.saferoute.app.core.emergency.SmsOutcome
import com.saferoute.app.core.emergency.SosAlertStatus
import com.saferoute.app.core.emergency.SosDispatch
import com.saferoute.app.core.emergency.SosTrail
import com.saferoute.app.core.emergency.SosTrailMode
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.feature.emergency.AndroidSmsGateway
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The SMS gateway with one thing added: when the phone did not take a text, the reason is
 * noted as a fixed word (ADR 0028). It changes nothing about sending: the outcome is
 * handed back exactly as it came, and noting it cannot fail or wait.
 *
 * It never sees more than it passes on, and it notes neither the number nor the text. A
 * text that was sent is not noted at all: no report ever says that an SOS happened.
 * Retries repeat every 30 seconds, so the same reason is noted once, not each time.
 */
@Singleton
class ReportingSmsGateway @Inject constructor(
    private val gateway: AndroidSmsGateway,
    private val reporter: DiagnosticsReporter,
) : SmsGateway {
    private val lastNoted = AtomicReference<SmsFailure?>(null)

    override suspend fun send(phoneE164: String, text: String): SmsOutcome {
        val outcome = gateway.send(phoneE164, text)
        noteOutcome(outcome)
        return outcome
    }

    internal fun noteOutcome(outcome: SmsOutcome) {
        val failure = when (outcome) {
            SmsOutcome.Sent -> null
            is SmsOutcome.Retryable -> SmsFailure.fromWord(outcome.category)
            is SmsOutcome.Final -> SmsFailure.fromWord(outcome.category)
        }
        if (lastNoted.getAndSet(failure) != failure && failure != null) {
            reporter.report(DiagnosticEvent.SmsSendFailed(failure))
        }
    }
}

/**
 * Watches what the app already publishes and notes three things (ADR 0028). It only
 * listens: no SOS code calls it or waits for it, and it reads no contact and no position.
 *
 * - Unsent alerts were given up after the retry limit: `ALERTS_GIVEN_UP`.
 * - During an SOS no position could be had: `LOCATION_UNAVAILABLE` (the fact, never a place).
 * - The session left the signed-in states: the in-memory list of categories is emptied.
 */
@Singleton
class DiagnosticsObservers @Inject constructor(
    private val dispatch: SosDispatch,
    private val trail: SosTrail,
    private val session: Session,
    private val reporter: DiagnosticsReporter,
    private val log: DiagnosticsLog,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() = observeDiagnostics(
        scope = scope,
        alertsGivenUp = dispatch.status.map { it is SosAlertStatus.Automatic && it.retryingStopped },
        locationUnavailable = trail.state.map { it.mode == SosTrailMode.UNAVAILABLE },
        sessionLeft = session.state.map { it is SessionState.SignedOut || it is SessionState.Blocked },
        reporter = reporter,
        log = log,
    )
}

/**
 * The listening itself, on three yes/no streams, so that it can be tested without an SOS.
 * Each event is noted when its stream turns to yes, not again while it stays yes.
 */
internal fun observeDiagnostics(
    scope: CoroutineScope,
    alertsGivenUp: Flow<Boolean>,
    locationUnavailable: Flow<Boolean>,
    sessionLeft: Flow<Boolean>,
    reporter: DiagnosticsReporter,
    log: DiagnosticsLog,
) {
    scope.launch {
        alertsGivenUp.distinctUntilChanged().collect { if (it) reporter.report(DiagnosticEvent.AlertsGivenUp) }
    }
    scope.launch {
        locationUnavailable.distinctUntilChanged()
            .collect { if (it) reporter.report(DiagnosticEvent.LocationUnavailable) }
    }
    scope.launch {
        sessionLeft.distinctUntilChanged().collect { if (it) log.clear() }
    }
}
