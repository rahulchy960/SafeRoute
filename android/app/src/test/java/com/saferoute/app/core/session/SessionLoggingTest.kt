// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.auth.FAKE_ID_TOKEN
import com.saferoute.app.core.auth.FAKE_PHONE_E164
import com.saferoute.app.core.auth.FAKE_REFRESHED_ID_TOKEN
import com.saferoute.app.core.auth.FAKE_SMS_CODE
import com.saferoute.app.core.auth.FAKE_VERIFICATION_ID
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.auth.FirebaseIdTokenProvider
import com.saferoute.app.core.auth.SignInResult
import com.saferoute.app.core.auth.normaliseIndianMobile
import com.saferoute.app.core.network.ApiConfig
import com.saferoute.app.core.network.FIXED_CLOCK
import com.saferoute.app.core.network.ME_JSON
import com.saferoute.app.core.network.RecordingSleeper
import com.saferoute.app.core.network.TEST_VERSION
import com.saferoute.app.core.network.di.newApiHttpClient
import com.saferoute.app.core.network.di.newApiRetrofit
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.problemResponse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * Signs in and creates an account with debug logging switched ON, exactly as a debug build
 * does (the real Logcat logger, the real token provider on a fake gateway), then reads
 * everything the app wrote to Android's log. No token, phone number, SMS code or verification
 * id may be in it.
 *
 * Robolectric's `ShadowLog` records every `android.util.Log` call made during the test.
 */
@RunWith(AndroidJUnit4::class)
class SessionLoggingTest {

    private val server = MockWebServer()
    private val api = FakeApi()

    @Before
    fun setUp() {
        ShadowLog.clear()
        server.dispatcher = api
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `sign-in and bootstrap write no token, phone number or code to the log`() = runTest {
        api.on(GET_ME, unauthorizedResponse(), problemResponse(403, "bootstrap_required"), ok(ME_JSON))
            .on(POST_BOOTSTRAP, ok(ME_JSON, code = 201))
            .on(GET_CONSENTS, ok(consentsJson(consentJson())))
        val gateway = FakePhoneAuthGateway(signedIn = false)
        val config = ApiConfig(server.url("/"), isConfigured = true, TEST_VERSION, debugLogging = true)
        // LogcatNetworkLog is the default: the lines go to android.util.Log.
        val client = newApiHttpClient(config, FirebaseIdTokenProvider(gateway), FIXED_CLOCK, RecordingSleeper())
        val meApi = newApiRetrofit(config) { client }.create(MeApi::class.java)
        val store = InMemorySessionStore(ONBOARDED)
        val repository = SessionRepository(gateway, store, meApi, { LOCALE_ENGLISH }, immediateAppScope())

        val phone = normaliseIndianMobile("90000 00001")
        assertEquals("+919000000001", phone)
        assertEquals(SignInResult.Success, gateway.verifyCode(FAKE_VERIFICATION_ID, FAKE_SMS_CODE))
        repository.refresh()

        assertEquals(SessionState.Ready, repository.state.value)
        // The first request was answered 401, so both the normal and the refreshed token were
        // sent to the server.
        val sent = api.requests.mapNotNull { it.authorization }
        assertTrue(sent.contains("Bearer $FAKE_ID_TOKEN"))
        assertTrue(sent.contains("Bearer $FAKE_REFRESHED_ID_TOKEN"))

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable ?: ""}" }
        assertTrue("the debug logger must have written its lines", logged.contains("/v1/me/bootstrap"))
        val secrets = listOf(
            FAKE_ID_TOKEN,
            FAKE_REFRESHED_ID_TOKEN,
            FAKE_PHONE_E164,
            "+919000000001",
            "9000000001",
            FAKE_SMS_CODE,
            FAKE_VERIFICATION_ID,
            "Bearer",
            "Authorization",
        )
        for (secret in secrets) assertFalse(secret, logged.contains(secret))
        // Request bodies are never logged either.
        assertFalse(logged.contains("ageConfirmed"))
        assertFalse(logged.contains(NOTICE_VERSION))
    }
}
