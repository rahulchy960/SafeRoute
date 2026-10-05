// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.network.auth.IdTokenProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The production Hilt graph binds sign-in to Firebase.
 *
 * The test only looks at which classes are bound. It does not call them: the Firebase SDK is
 * not started in JVM tests (there is no `FirebaseApp` under Robolectric), which is also why
 * building the graph must not touch Firebase (`Lazy<FirebaseAuth>` in the gateway). Tests that
 * need sign-in replace [com.saferoute.app.core.auth.di.AuthModule] with [FakePhoneAuthGateway].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class AuthWiringTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var gateway: PhoneAuthGateway

    @Inject lateinit var sameGateway: PhoneAuthGateway

    @Inject lateinit var tokens: IdTokenProvider

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `the gateway is the Firebase one, and there is only one`() {
        assertTrue(gateway is FirebasePhoneAuthGateway)
        assertSame(gateway, sameGateway)
    }

    @Test
    fun `the network layer gets its tokens from sign-in`() {
        assertTrue(tokens is FirebaseIdTokenProvider)
    }
}
