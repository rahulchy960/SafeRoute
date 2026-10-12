// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import javax.inject.Inject
import javax.inject.Singleton

/** The answer to "start an SOS from this entry point". */
sealed interface StartSosResult {

    /**
     * The user has not agreed to the notice that describes SOS alerts: nothing was started
     * and nothing was written down. The screen offers Call 112 and the way to the setup.
     */
    data object NotSetUp : StartSosResult

    /** A new five-second countdown began. */
    data class Started(val record: SosRecord) : StartSosResult

    /** An emergency was already going on; it is shown instead of a second one. */
    data class AlreadyRunning(val record: SosRecord) : StartSosResult
}

/**
 * The ONE way an SOS starts, whatever the user touched: the hold in the app, the Quick
 * Settings tile, the home-screen widget or the pinned notification (ADR 0027, "Entry
 * points"). Each of them only says which it was.
 *
 * The consent gate comes first, and it is the same gate for all of them: without agreement
 * to the notice the app shows today, no countdown starts ([StartSosResult.NotSetUp]). With
 * it, [SosRunner] starts the countdown, or returns the emergency that is already going on.
 *
 * It asks for no permission, shows nothing and waits for nothing but the phone's own
 * records: the caller is the emergency screen, which is visible when it calls.
 */
@Singleton
class StartSos @Inject constructor(
    private val policy: SosAlertPolicy,
    private val runner: SosRunner,
) {
    suspend operator fun invoke(entryPoint: SosEntryPoint): StartSosResult {
        if (!policy.alertsAllowed()) return StartSosResult.NotSetUp
        return when (val start = runner.start(entryPoint)) {
            is SosStart.Started -> StartSosResult.Started(start.record)
            is SosStart.AlreadyActive -> StartSosResult.AlreadyRunning(start.record)
        }
    }
}
