// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.di

import com.saferoute.app.BuildConfig
import com.saferoute.app.core.network.ApiConfig
import com.saferoute.app.core.network.apiConfigFromBuild
import com.saferoute.app.core.network.auth.AuthInterceptor
import com.saferoute.app.core.network.auth.IdTokenProvider
import com.saferoute.app.core.network.auth.TokenAuthenticator
import com.saferoute.app.core.network.generated.api.ContactsApi
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.api.OperationalApi
import com.saferoute.app.core.network.generated.api.RoutingApi
import com.saferoute.app.core.network.generated.api.SearchApi
import com.saferoute.app.core.network.interceptor.LogcatNetworkLog
import com.saferoute.app.core.network.interceptor.NetworkLog
import com.saferoute.app.core.network.interceptor.RequestIdInterceptor
import com.saferoute.app.core.network.interceptor.SafeLoggingInterceptor
import com.saferoute.app.core.network.interceptor.UserAgentInterceptor
import com.saferoute.app.core.network.networkJson
import com.saferoute.app.core.network.retry.RetryInterceptor
import com.saferoute.app.core.network.retry.Sleeper
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds the HTTP client the whole app shares (ADR 0009).
 *
 * OkHttp sends the requests. The interceptors run in this order for every request: request id,
 * user agent, token, retry, and in debug builds the safe logger. The authenticator handles 401.
 *
 * Where ID tokens come from is bound in `core/auth/di/AuthModule` (Firebase, since P009b).
 */
internal fun newApiHttpClient(
    config: ApiConfig,
    tokens: IdTokenProvider,
    clock: Clock,
    sleeper: Sleeper = Sleeper { Thread.sleep(it) },
    log: NetworkLog = LogcatNetworkLog,
): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    // The whole call, retries included.
    .callTimeout(45, TimeUnit.SECONDS)
    .addInterceptor(RequestIdInterceptor())
    .addInterceptor(UserAgentInterceptor(config.versionName))
    .addInterceptor(AuthInterceptor(config, tokens))
    .addInterceptor(RetryInterceptor(clock, sleeper))
    .apply { if (config.debugLogging) addInterceptor(SafeLoggingInterceptor(log)) }
    .authenticator(TokenAuthenticator(config, tokens))
    .build()

/**
 * Retrofit turns the generated interfaces into working objects: calling `getHealth()` builds
 * the request, sends it with OkHttp and decodes the JSON answer.
 *
 * The client is passed lazily so that it is created on the first request (on a background
 * thread), not while the app starts.
 */
internal fun newApiRetrofit(config: ApiConfig, client: () -> OkHttpClient): Retrofit =
    Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .callFactory { request -> client().newCall(request) }
        .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
        .build()

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideApiConfig(): ApiConfig = apiConfigFromBuild(
        baseUrl = BuildConfig.API_BASE_URL,
        isConfigured = BuildConfig.API_BASE_URL_CONFIGURED,
        versionName = BuildConfig.VERSION_NAME,
        debug = BuildConfig.DEBUG,
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(config: ApiConfig, tokens: IdTokenProvider, clock: Clock): OkHttpClient =
        newApiHttpClient(config, tokens, clock)

    @Provides
    @Singleton
    fun provideRetrofit(config: ApiConfig, client: Lazy<OkHttpClient>): Retrofit =
        newApiRetrofit(config) { client.get() }

    @Provides
    @Singleton
    fun provideOperationalApi(retrofit: Retrofit): OperationalApi =
        retrofit.create(OperationalApi::class.java)

    @Provides
    @Singleton
    fun provideMeApi(retrofit: Retrofit): MeApi = retrofit.create(MeApi::class.java)

    @Provides
    @Singleton
    fun provideContactsApi(retrofit: Retrofit): ContactsApi = retrofit.create(ContactsApi::class.java)

    @Provides
    @Singleton
    fun provideSearchApi(retrofit: Retrofit): SearchApi = retrofit.create(SearchApi::class.java)

    @Provides
    @Singleton
    fun provideRoutingApi(retrofit: Retrofit): RoutingApi = retrofit.create(RoutingApi::class.java)
}
