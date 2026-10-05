// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.auth.FirebaseIdTokenProvider
import com.saferoute.app.core.auth.PhoneAuthGateway
import com.saferoute.app.core.auth.di.AuthModule
import com.saferoute.app.core.network.auth.IdTokenProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The session code as Hilt builds it: the real DataStore file (in Robolectric's app storage),
 * the real locale lookup and the real API client, with sign-in replaced by a fake. Nobody is
 * signed in, so no request is ever sent.
 *
 * `@UninstallModules` removes the production sign-in bindings for this test; `@BindValue` puts
 * the fields below in their place.
 */
@HiltAndroidTest
@UninstallModules(AuthModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class SessionWiringTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    private val fakeGateway = FakePhoneAuthGateway(signedIn = false)

    @BindValue
    @JvmField
    val gateway: PhoneAuthGateway = fakeGateway

    @BindValue
    @JvmField
    val tokens: IdTokenProvider = FirebaseIdTokenProvider(fakeGateway)

    @Inject lateinit var session: SessionRepository

    @Inject lateinit var sameSession: SessionRepository

    @Inject lateinit var store: SessionStore

    @Inject lateinit var appLocale: AppLocale

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `one repository for the whole app, starting as Loading`() {
        assertSame(session, sameSession)
        assertEquals(SessionState.Loading, session.state.value)
    }

    @Test
    fun `a fresh install goes through age and consent using the real store`() = runTest {
        assertTrue(store is DataStoreSessionStore)
        session.refresh()
        assertEquals(SessionState.NeedsAge, session.state.value)

        session.confirmAdult()
        assertEquals(SessionState.NeedsConsent, session.state.value)

        session.acceptNotice(appLocale.current())
        assertEquals(SessionState.SignedOut, session.state.value)

        val flags = store.read()
        assertTrue(flags.ageConfirmed)
        assertEquals(NOTICE_VERSION, flags.acceptedNoticeVersion)
        assertEquals(LOCALE_ENGLISH, flags.acceptedNoticeLocale)
        assertTrue(fakeGateway.calls.isEmpty())
        assertTrue(fakeGateway.tokenRequests.isEmpty())
    }

    @Test
    @Config(qualifiers = "bn")
    fun `the UI locale follows the app's resources`() {
        assertEquals(LOCALE_BENGALI, appLocale.current())
    }

    @Test
    @Config(qualifiers = "fr")
    fun `an unsupported language falls back to English`() {
        assertEquals(LOCALE_ENGLISH, appLocale.current())
    }
}
