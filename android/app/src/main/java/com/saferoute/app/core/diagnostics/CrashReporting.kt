// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import com.saferoute.app.core.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What this build is, as far as diagnostics cares.
 *
 * @property reportsAllowed false in a debug build unless the developer set the Gradle
 * property `saferoute.debugCrashReports`; always true in a release build. False means the
 * person's choice is ignored and nothing is collected.
 */
data class DiagnosticsBuild(
    val reportsAllowed: Boolean,
    val debug: Boolean,
    val versionName: String,
    val buildType: String,
    val androidApi: Int,
)

/**
 * Connects the person's choice to the reporter (ADR 0028). Started once by the application.
 *
 * Collection is on only while BOTH hold: the person switched "Send crash reports" on, and
 * the build allows it. Until the stored choice has been read, nothing is collected: the
 * manifest switches the library off, and so does every start that finds the choice off.
 */
@Singleton
class CrashReporting @Inject constructor(
    private val preferences: DiagnosticsPreferences,
    private val reporter: DiagnosticsReporter,
    private val build: DiagnosticsBuild,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        installHandler()
        reporter.setKey(DiagnosticKey.APP_VERSION, build.versionName)
        reporter.setKey(DiagnosticKey.BUILD_TYPE, build.buildType)
        reporter.setKey(DiagnosticKey.ANDROID_API, build.androidApi.toString())
        scope.launch {
            preferences.settings
                .map { it.crashReports && build.reportsAllowed }
                .distinctUntilChanged()
                .collect(reporter::setCollecting)
        }
    }

    /**
     * Puts [RedactingExceptionHandler] in front of whatever handles a crash today. The
     * crash library installed its own handler before the application's `onCreate` ran, so
     * it now receives the cleaned error instead of the original.
     */
    private fun installHandler() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is RedactingExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            RedactingExceptionHandler(next = current, keepOriginal = { build.debug && !reporter.collecting }),
        )
    }
}

/**
 * Called by Android when an error was caught by nobody and the app is about to close. It
 * hands the error on with its message replaced ([RedactedError]), so neither the crash
 * report nor the system log quotes what the app was working on.
 *
 * The one exception is for the developer: a debug build that is not collecting keeps the
 * original, so that the message can be read in Logcat. Such a crash can still be stored on
 * the phone by the library; the reporter deletes stored crashes before it ever switches
 * collection on.
 */
internal class RedactingExceptionHandler(
    private val next: Thread.UncaughtExceptionHandler?,
    private val keepOriginal: () -> Boolean,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        val passed = if (keepOriginal()) error else RedactedError.of(error, ErrorCategory.UNCAUGHT)
        next?.uncaughtException(thread, passed)
    }
}
