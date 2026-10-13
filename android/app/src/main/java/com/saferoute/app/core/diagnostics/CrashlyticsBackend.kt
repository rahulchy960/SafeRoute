// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import com.google.firebase.crashlytics.FirebaseCrashlytics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONLY file that uses the Crashlytics library (ADR 0028; `DiagnosticsBoundaryTest`).
 *
 * It is deliberately thin: four calls, each of which receives something that
 * [RedactingDiagnosticsReporter] has already cleaned. The calls this file does NOT make are
 * part of the design: no `setUserId` (a report names nobody), no `log` (no free text) and
 * no `sendUnsentReports`.
 *
 * `FirebaseCrashlytics.getInstance()` is asked for on each call instead of being kept: the
 * library is started by Firebase's own start-up code, and in a unit test, where Firebase
 * is not started, the call throws and the reporter drops it.
 */
@Singleton
class CrashlyticsBackend @Inject constructor() : CrashBackend {
    private val crashlytics: FirebaseCrashlytics get() = FirebaseCrashlytics.getInstance()

    /** The value is remembered by the library across starts; "off" applies from the next start. */
    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
    }

    override fun deleteUnsentReports() {
        crashlytics.deleteUnsentReports()
    }

    override fun setKey(key: String, value: String) {
        crashlytics.setCustomKey(key, value)
    }

    override fun recordNonFatal(error: RedactedError) {
        crashlytics.recordException(error)
    }
}
