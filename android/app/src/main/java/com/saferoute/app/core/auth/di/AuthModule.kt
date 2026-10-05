// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth.di

import com.google.firebase.auth.FirebaseAuth
import com.saferoute.app.core.auth.FirebaseIdTokenProvider
import com.saferoute.app.core.auth.FirebasePhoneAuthGateway
import com.saferoute.app.core.auth.PhoneAuthGateway
import com.saferoute.app.core.network.auth.IdTokenProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Sign-in for the whole app (ADR 0012). Tests swap this module for a fake gateway with
 * `@UninstallModules(AuthModule::class)`.
 */
@Module
@InstallIn(SingletonComponent::class)
interface AuthModule {

    @Binds
    fun bindPhoneAuthGateway(gateway: FirebasePhoneAuthGateway): PhoneAuthGateway

    /** Where the network layer gets ID tokens from (replaces P008's signed-out stand-in). */
    @Binds
    fun bindIdTokenProvider(provider: FirebaseIdTokenProvider): IdTokenProvider

    companion object {

        /**
         * Firebase configures itself when the app process starts, from the values the Google
         * Services Gradle plugin generated out of `google-services.json`.
         */
        @Provides
        fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
    }
}
