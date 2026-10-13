// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * The rules of crash reporting (ADR 0028), tested at the seam to the library: what reaches
 * [RecordingCrashBackend] is what would reach Crashlytics.
 *
 * Runs with Robolectric only so that the log can be read back (`ShadowLog`).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsReporterTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val backend = RecordingCrashBackend()
    private val log = DiagnosticsLog(clock)
    private val reporter = RedactingDiagnosticsReporter(backend, log)

    // Made-up values of the kinds that must never be reported.
    private val number = "+910000100001"
    private val secret = "typed text about a place 22.5, 88.25"

    @Test
    fun `nothing reaches the library before the person opted in`() {
        reporter.setKey(DiagnosticKey.BUILD_TYPE, "debug")
        reporter.report(DiagnosticEvent.RecoveryShown)
        reporter.report(IllegalStateException(secret), ErrorCategory.STORAGE)

        assertFalse(reporter.collecting)
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test
    fun `opting in deletes what was stored before and then switches collection on`() {
        reporter.setKey(DiagnosticKey.BUILD_TYPE, "debug")

        reporter.setCollecting(true)

        assertTrue(reporter.collecting)
        assertEquals(listOf("deleteUnsent", "enabled=true", "key"), backend.calls)
        assertEquals(mapOf("buildType" to "debug"), backend.keys)
    }

    @Test
    fun `opting out stops reports at once and deletes what was not sent`() {
        reporter.setCollecting(true)
        backend.calls.clear()

        reporter.setCollecting(false)
        reporter.report(DiagnosticEvent.ServiceKilled)
        reporter.report(RuntimeException(), ErrorCategory.BACKGROUND_WORK)

        assertFalse(reporter.collecting)
        assertEquals(listOf("enabled=false", "deleteUnsent"), backend.calls)
    }

    @Test
    fun `a start with the choice off switches the library off and deletes stored crashes`() {
        reporter.setCollecting(false)

        assertEquals(listOf("enabled=false", "deleteUnsent"), backend.calls)
    }

    @Test
    fun `a key keeps only a value its list accepts`() {
        reporter.setCollecting(true)

        reporter.setKey(DiagnosticKey.NETWORK_TYPE, "wifi")
        reporter.setKey(DiagnosticKey.OEM_FAMILY, number)
        reporter.setKey(DiagnosticKey.LOCALE, secret)
        reporter.setKey(DiagnosticKey.SMS_MODE, "Test Contact 1")

        assertEquals(mapOf("networkType" to "wifi"), backend.keys)
    }

    @Test
    fun `an event is reported as its fixed category and nothing else`() {
        reporter.setCollecting(true)

        reporter.report(DiagnosticEvent.SmsSendFailed(SmsFailure.NO_SERVICE))
        reporter.report(DiagnosticEvent.ApiError(ApiErrorKind.UNAVAILABLE))
        reporter.report(DiagnosticEvent.AlertsGivenUp)

        assertEquals(
            listOf("SMS_SEND_FAILED(no_service)", "API_ERROR(unavailable)", "ALERTS_GIVEN_UP"),
            backend.errors.map { it.message },
        )
        assertTrue(backend.errors.all { it.cause == null && it.stackTrace.isNotEmpty() })
    }

    @Test
    fun `the events are the agreed list, and none is about an SOS that worked`() {
        val all = listOf(
            DiagnosticEvent.SmsSendFailed(SmsFailure.OTHER),
            DiagnosticEvent.ServiceKilled,
            DiagnosticEvent.RecoveryShown,
            DiagnosticEvent.LocationUnavailable,
            DiagnosticEvent.ApiError(ApiErrorKind.UNEXPECTED),
            DiagnosticEvent.AlertsGivenUp,
        )
        // The compiler checks this list: a new kind of event does not compile until it is named here.
        fun known(event: DiagnosticEvent): Boolean = when (event) {
            is DiagnosticEvent.SmsSendFailed, DiagnosticEvent.ServiceKilled, DiagnosticEvent.RecoveryShown,
            DiagnosticEvent.LocationUnavailable, is DiagnosticEvent.ApiError, DiagnosticEvent.AlertsGivenUp,
            -> true
        }
        assertTrue(all.all(::known))
        val names = all.map { it.category.substringBefore('(') }
        assertEquals(
            listOf(
                "SMS_SEND_FAILED", "SERVICE_KILLED", "RECOVERY_SHOWN", "LOCATION_UNAVAILABLE",
                "API_ERROR", "ALERTS_GIVEN_UP",
            ),
            names,
        )
        val forbidden = listOf("START", "SUCCESS", "SENT", "CONTACT", "SEARCH", "ROUTE", "TRIGGER")
        assertTrue(names.none { name -> forbidden.any { it in name } })
        // The values an event can carry are enums of fixed words, and each word is clean.
        for (word in SmsFailure.entries.map { it.word } + ApiErrorKind.entries.map { it.word }) {
            assertTrue(word, Regex("[a-z_]{1,20}").matches(word))
        }
    }

    @Test
    fun `an unknown SMS failure word is not passed on`() {
        assertEquals(SmsFailure.NO_SERVICE, SmsFailure.fromWord("no_service"))
        assertEquals(SmsFailure.OTHER, SmsFailure.fromWord(number))
        assertEquals(SmsFailure.OTHER, SmsFailure.fromWord(null))
    }

    @Test
    fun `an error's message is replaced by its class and a category, causes included`() {
        reporter.setCollecting(true)
        val original = IllegalStateException("cannot read $number", NumberFormatException(secret))

        reporter.report(original, ErrorCategory.STORAGE)

        val sent = backend.errors.single()
        assertEquals("java.lang.IllegalStateException [STORAGE]", sent.message)
        assertEquals("java.lang.NumberFormatException [STORAGE]", sent.cause?.message)
        assertNull(sent.cause?.cause)
        assertEquals(original.stackTrace.toList(), sent.stackTrace.toList())
        val everything = generateSequence<Throwable>(sent) { it.cause }.joinToString { it.toString() }
        assertFalse(everything.contains(number) || everything.contains("22.5") || everything.contains("typed"))
    }

    @Test
    fun `a long chain of causes is cut and an error is not redacted twice`() {
        var error: Throwable = RuntimeException(secret)
        repeat(10) { error = RuntimeException(secret, error) }

        val redacted = RedactedError.of(error, ErrorCategory.UNCAUGHT)

        assertEquals(5, generateSequence<Throwable>(redacted) { it.cause }.count())
        assertSame(redacted, RedactedError.of(redacted, ErrorCategory.TEST))
    }

    @Test
    fun `a crash nobody caught is handed on with its message replaced`() {
        val received = mutableListOf<Throwable>()
        val next = Thread.UncaughtExceptionHandler { _, error -> received += error }
        val crash = IllegalArgumentException("bad input $number")

        RedactingExceptionHandler(next, keepOriginal = { false }).uncaughtException(Thread.currentThread(), crash)
        RedactingExceptionHandler(next, keepOriginal = { true }).uncaughtException(Thread.currentThread(), crash)
        RedactingExceptionHandler(null, keepOriginal = { false }).uncaughtException(Thread.currentThread(), crash)

        assertEquals("java.lang.IllegalArgumentException [UNCAUGHT]", received[0].message)
        assertTrue(received[0] is RedactedError)
        assertSame(crash, received[1])
        assertEquals(2, received.size)
    }

    @Test
    fun `a library that fails never breaks the caller`() {
        backend.failing = true

        reporter.setCollecting(true)
        reporter.setKey(DiagnosticKey.BUILD_TYPE, "debug")
        reporter.report(DiagnosticEvent.LocationUnavailable)
        reporter.report(RuntimeException(), ErrorCategory.TEST)
        reporter.setCollecting(false)

        assertEquals(listOf("LOCATION_UNAVAILABLE", "ERROR(TEST)"), log.entries().map { it.category })
    }

    @Test
    fun `the list on the phone holds categories only, the last twenty, also while off`() {
        reporter.report(IllegalStateException(secret), ErrorCategory.STORAGE)
        repeat(DiagnosticsLog.CAPACITY + 5) { reporter.report(DiagnosticEvent.ApiError(ApiErrorKind.NO_CONNECTION)) }
        reporter.report(DiagnosticEvent.RecoveryShown)

        val entries = log.entries()
        assertEquals(DiagnosticsLog.CAPACITY, entries.size)
        assertEquals("RECOVERY_SHOWN", entries.last().category)
        assertEquals(setOf("API_ERROR(no_connection)", "RECOVERY_SHOWN"), entries.map { it.category }.toSet())
        assertTrue(entries.all { it.at == clock.instant() })
        assertTrue(entries.all { Regex("[A-Z_]+(\\([a-zA-Z_]+\\))?").matches(it.category) })

        log.clear()
        assertEquals(emptyList<DiagnosticsLog.Entry>(), log.entries())
    }

    @Test
    fun `reporting writes nothing to the log`() {
        ShadowLog.clear()
        reporter.setCollecting(true)
        reporter.setKey(DiagnosticKey.OEM_FAMILY, number)
        reporter.report(IllegalStateException("$secret $number"), ErrorCategory.STORAGE)
        reporter.report(DiagnosticEvent.SmsSendFailed(SmsFailure.fromWord(number)))
        reporter.setCollecting(false)

        // Covers the app's own log lines in this process (Logcat). It says nothing about
        // what the library writes on a phone: that is a check for a person (script D3).
        val lines = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        assertFalse(lines.contains(number) || lines.contains("22.5") || lines.contains("typed text"))
    }

    @Test
    fun `collection follows the choice and the build, and is off by default`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = FakeDiagnosticsPreferences()
        CrashReporting(preferences, reporter, TEST_BUILD, backgroundScope).start()

        assertFalse(reporter.collecting)
        assertEquals(listOf("enabled=false", "deleteUnsent"), backend.calls)
        assertEquals(emptyList<RedactedError>(), backend.errors)

        preferences.setCrashReports(true)
        assertTrue(reporter.collecting)
        assertEquals(mapOf("appVersion" to "1.0", "buildType" to "debug", "androidApi" to "36"), backend.keys)

        preferences.setCrashReports(false)
        assertFalse(reporter.collecting)
        assertEquals("deleteUnsent", backend.calls.last())
    }

    @Test
    fun `a build that does not allow reports ignores the choice`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = FakeDiagnosticsPreferences(DiagnosticsSettings(crashReports = true, asked = true))
        CrashReporting(preferences, reporter, TEST_BUILD.copy(reportsAllowed = false), backgroundScope).start()

        reporter.report(DiagnosticEvent.ServiceKilled)

        assertFalse(reporter.collecting)
        assertEquals(listOf("enabled=false", "deleteUnsent"), backend.calls)
    }
}
