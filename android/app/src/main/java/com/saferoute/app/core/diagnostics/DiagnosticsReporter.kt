// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one way the app reports a problem (ADR 0028). The rest of the app sees only this:
 * typed events, errors with a category, and a few allowlisted facts about the phone.
 * There is no method that takes free text and none that names a user.
 *
 * Every event is also noted, as its category only, in [DiagnosticsLog], whether or not
 * crash reports are switched on: that list never leaves the phone by itself.
 */
interface DiagnosticsReporter {
    /** True while reports are being collected in this run of the app. */
    val collecting: Boolean

    /** Called by [CrashReporting] only: the person's choice, already combined with the build's. */
    fun setCollecting(enabled: Boolean)

    /** Attaches a fact to later reports. A value the key does not accept is dropped. */
    fun setKey(key: DiagnosticKey, value: String)

    fun report(event: DiagnosticEvent)

    /** Reports a caught error. Its message is replaced (see [RedactedError]). */
    fun report(error: Throwable, category: ErrorCategory)
}

/**
 * What stands between [DiagnosticsReporter] and the crash-reporting library. Only
 * `CrashlyticsBackend.kt` implements it for real (`DiagnosticsBoundaryTest`); tests use a
 * recording one, so the rules in [RedactingDiagnosticsReporter] are tested without the library.
 */
interface CrashBackend {
    fun setCollectionEnabled(enabled: Boolean)

    /** Deletes reports that were stored on the phone and not sent yet. */
    fun deleteUnsentReports()

    fun setKey(key: String, value: String)

    fun recordNonFatal(error: RedactedError)
}

@Singleton
class RedactingDiagnosticsReporter @Inject constructor(
    private val backend: CrashBackend,
    private val log: DiagnosticsLog,
) : DiagnosticsReporter {
    private val enabled = AtomicBoolean(false)

    // Kept so that keys set before the choice is known are attached once collection starts.
    private val keys = ConcurrentHashMap<DiagnosticKey, String>()

    override val collecting: Boolean get() = enabled.get()

    override fun setCollecting(enabled: Boolean) {
        if (enabled) {
            // The library keeps a crash on the phone even while collection is off and would
            // send it now. Whatever was stored before the person agreed is deleted first.
            if (!this.enabled.get()) guarded { backend.deleteUnsentReports() }
            guarded { backend.setCollectionEnabled(true) }
            this.enabled.set(true)
            keys.forEach { (key, value) -> guarded { backend.setKey(key.key, value) } }
        } else {
            // First our own switch, which stops non-fatal reports at once; the library's
            // own switch only takes effect at the next start.
            this.enabled.set(false)
            guarded { backend.setCollectionEnabled(false) }
            guarded { backend.deleteUnsentReports() }
        }
    }

    override fun setKey(key: DiagnosticKey, value: String) {
        val clean = key.clean(value) ?: return
        keys[key] = clean
        if (enabled.get()) guarded { backend.setKey(key.key, clean) }
    }

    override fun report(event: DiagnosticEvent) {
        log.add(event.category)
        if (enabled.get()) guarded { backend.recordNonFatal(RedactedError.ofEvent(event)) }
    }

    override fun report(error: Throwable, category: ErrorCategory) {
        log.add("ERROR(${category.name})")
        if (enabled.get()) guarded { backend.recordNonFatal(RedactedError.of(error, category)) }
    }

    /** Reporting must never be the thing that breaks: a failure here is dropped, unlogged. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // Nothing to do and nowhere safe to say it.
        }
    }
}
