// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics.di

import android.os.Build
import com.saferoute.app.BuildConfig
import com.saferoute.app.core.diagnostics.CrashBackend
import com.saferoute.app.core.diagnostics.CrashlyticsBackend
import com.saferoute.app.core.diagnostics.DataStoreDiagnosticsPreferences
import com.saferoute.app.core.diagnostics.DiagnosticsBuild
import com.saferoute.app.core.diagnostics.DiagnosticsPreferences
import com.saferoute.app.core.diagnostics.DiagnosticsReporter
import com.saferoute.app.core.diagnostics.RedactingDiagnosticsReporter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The real reporter and its library. Hilt tests replace this module with
 * `FakeDiagnosticsModule`, so no test reaches the crash-reporting library.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DiagnosticsModule {
    @Binds
    fun bindReporter(reporter: RedactingDiagnosticsReporter): DiagnosticsReporter

    @Binds
    fun bindBackend(backend: CrashlyticsBackend): CrashBackend

    @Binds
    fun bindPreferences(preferences: DataStoreDiagnosticsPreferences): DiagnosticsPreferences

    companion object {
        @Provides
        fun provideBuild(): DiagnosticsBuild = DiagnosticsBuild(
            reportsAllowed = BuildConfig.CRASH_REPORTS_ALLOWED,
            debug = BuildConfig.DEBUG,
            versionName = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidApi = Build.VERSION.SDK_INT,
        )
    }
}
