// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics.di

import com.saferoute.app.BuildConfig
import com.saferoute.app.core.diagnostics.DiagnosticsFactsSource
import com.saferoute.app.feature.diagnostics.AndroidDiagnosticsFacts
import com.saferoute.app.feature.diagnostics.SupportAddress
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What diagnostics reads from the phone and the build. */
@Module
@InstallIn(SingletonComponent::class)
interface DiagnosticsFeatureModule {
    @Binds
    fun bindFacts(facts: AndroidDiagnosticsFacts): DiagnosticsFactsSource

    companion object {
        /** From the Gradle property `saferoute.supportEmail`; empty hides the e-mail button. */
        @Provides
        fun provideSupportAddress(): SupportAddress = SupportAddress(BuildConfig.SUPPORT_EMAIL)
    }
}
