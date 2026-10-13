// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import com.saferoute.app.core.diagnostics.di.DiagnosticsModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/** Stands in for the crash-reporting library and notes every call it receives, in order. */
class RecordingCrashBackend : CrashBackend {
    val calls = mutableListOf<String>()
    val keys = mutableMapOf<String, String>()
    val errors = mutableListOf<RedactedError>()

    /** Set to make every call fail, as the real library does when Firebase is not started. */
    var failing = false

    private fun call(name: String) {
        if (failing) throw IllegalStateException("not started")
        calls += name
    }

    override fun setCollectionEnabled(enabled: Boolean) = call("enabled=$enabled")

    override fun deleteUnsentReports() = call("deleteUnsent")

    override fun setKey(key: String, value: String) {
        call("key")
        keys[key] = value
    }

    override fun recordNonFatal(error: RedactedError) {
        call("nonFatal")
        errors += error
    }
}

/** What Hilt tests get instead of the real reporter: it only remembers what it was told. */
class FakeDiagnosticsReporter : DiagnosticsReporter {
    override var collecting = false
        private set
    val events = mutableListOf<DiagnosticEvent>()
    val errors = mutableListOf<Pair<String, ErrorCategory>>()
    val keys = mutableMapOf<DiagnosticKey, String>()

    override fun setCollecting(enabled: Boolean) {
        collecting = enabled
    }

    override fun setKey(key: DiagnosticKey, value: String) {
        keys[key] = value
    }

    override fun report(event: DiagnosticEvent) {
        events += event
    }

    override fun report(error: Throwable, category: ErrorCategory) {
        errors += error.javaClass.name to category
    }
}

class FakeDiagnosticsPreferences(initial: DiagnosticsSettings = DiagnosticsSettings()) : DiagnosticsPreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun setCrashReports(enabled: Boolean) {
        settings.value = DiagnosticsSettings(crashReports = enabled, asked = true)
    }

    override suspend fun setAsked() {
        settings.value = settings.value.copy(asked = true)
    }
}

val TEST_BUILD = DiagnosticsBuild(
    reportsAllowed = true,
    debug = true,
    versionName = "1.0",
    buildType = "debug",
    androidApi = 36,
)

/**
 * Replaces [DiagnosticsModule] in every Hilt test, so no test reaches the crash-reporting
 * library, and the choice starts as it does for a new person: off and not asked.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DiagnosticsModule::class])
object FakeDiagnosticsModule {
    @Provides
    @Singleton
    fun provideFakeReporter(): FakeDiagnosticsReporter = FakeDiagnosticsReporter()

    @Provides
    fun provideReporter(fake: FakeDiagnosticsReporter): DiagnosticsReporter = fake

    @Provides
    @Singleton
    fun provideFakePreferences(): FakeDiagnosticsPreferences = FakeDiagnosticsPreferences()

    @Provides
    fun providePreferences(fake: FakeDiagnosticsPreferences): DiagnosticsPreferences = fake

    @Provides
    fun provideBuild(): DiagnosticsBuild = TEST_BUILD
}
