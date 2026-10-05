// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.BuildConfig
import com.saferoute.app.core.auth.FirebaseIdTokenProvider
import com.saferoute.app.core.network.ApiConfig
import com.saferoute.app.core.network.auth.AuthInterceptor
import com.saferoute.app.core.network.auth.IdTokenProvider
import com.saferoute.app.core.network.auth.TokenAuthenticator
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.api.OperationalApi
import com.saferoute.app.core.network.interceptor.RequestIdInterceptor
import com.saferoute.app.core.network.interceptor.SafeLoggingInterceptor
import com.saferoute.app.core.network.interceptor.UserAgentInterceptor
import com.saferoute.app.core.network.retry.RetryInterceptor
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Hilt graph hands out the network layer as the app will use it. Nothing here prints the
 * base URL: on a developer's machine it is the real staging address.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class NetworkModuleTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var config: ApiConfig

    @Inject lateinit var client: OkHttpClient

    @Inject lateinit var sameClient: OkHttpClient

    @Inject lateinit var tokens: IdTokenProvider

    @Inject lateinit var operationalApi: OperationalApi

    @Inject lateinit var meApi: MeApi

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `the config comes from the build and is HTTPS`() {
        assertTrue("the base URL must be https", config.baseUrl.isHttps)
        assertEquals(BuildConfig.API_BASE_URL_CONFIGURED, config.isConfigured)
        assertEquals(BuildConfig.VERSION_NAME, config.versionName)
        assertEquals(BuildConfig.DEBUG, config.debugLogging)
    }

    @Test
    fun `tokens come from Firebase sign-in`() {
        // What it returns is covered by FirebaseIdTokenProviderTest and AuthWiringTest.
        assertTrue(tokens is FirebaseIdTokenProvider)
    }

    @Test
    fun `one client with the interceptors in order, the authenticator and the timeouts`() {
        assertSame(client, sameClient)
        val expected = listOf(
            RequestIdInterceptor::class,
            UserAgentInterceptor::class,
            AuthInterceptor::class,
            RetryInterceptor::class,
            // Unit tests run the debug build, which adds the safe logger.
            SafeLoggingInterceptor::class,
        )
        assertEquals(expected, client.interceptors.map { it::class })
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.authenticator is TokenAuthenticator)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(45_000, client.callTimeoutMillis)
    }

    @Test
    fun `the generated interfaces are provided`() {
        // Retrofit builds an implementation of each interface at runtime.
        assertTrue(java.lang.reflect.Proxy.isProxyClass(operationalApi.javaClass))
        assertTrue(java.lang.reflect.Proxy.isProxyClass(meApi.javaClass))
    }
}
