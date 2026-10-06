// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import com.saferoute.app.core.auth.FAKE_ID_TOKEN
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.network.FakeIdTokenProvider
import com.saferoute.app.core.network.ME_JSON
import com.saferoute.app.core.network.TestApi
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

/** Masking the phone number, and what Settings gets from `account()`. */
class AccountSummaryTest {

    private val server = MockWebServer()
    private val api = FakeApi()

    @Before
    fun setUp() {
        server.dispatcher = api
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun repository(signedIn: Boolean = true): SessionRepository {
        val tokens = FakeIdTokenProvider(token = FAKE_ID_TOKEN, refreshed = FAKE_ID_TOKEN)
        val meApi = TestApi(server, tokens).create<MeApi>()
        return SessionRepository(
            FakePhoneAuthGateway(signedIn),
            InMemorySessionStore(ONBOARDED),
            meApi,
            { LOCALE_ENGLISH },
            immediateAppScope(),
        )
    }

    @Test
    fun `an Indian number keeps the country code and its last three digits`() {
        assertEquals("+91 ••••• ••123", maskPhone("+910000000123"))
        assertEquals("+91 ••••• ••001", maskPhone("+919000000001"))
    }

    @Test
    fun `the masked form never contains more than three digits of the number`() {
        val masked = maskPhone("+919876543210")
        assertEquals("+91 ••••• ••210", masked)
        assertFalse(masked.contains("98765"))
        assertEquals(3, masked.removePrefix("+91").count(Char::isDigit))
    }

    @Test
    fun `anything that is not an Indian E164 number is hidden completely`() {
        val inputs = listOf("", "+14155550100", "9000000001", "+91900000000", "+9190000000012", "+91 90000 00001", "abc")
        for (input in inputs) {
            val masked = maskPhone(input)
            assertEquals(input, "•••••", masked)
            assertFalse(masked.any(Char::isDigit))
        }
    }

    @Test
    fun `account returns the masked phone, role, locale and granted purposes only`() = runTest {
        api.on(GET_ME, ok(ME_JSON)).on(
            GET_CONSENTS,
            ok(
                consentsJson(
                    consentJson(),
                    consentJson(purpose = "sos_alerts", status = "withdrawn"),
                    consentJson(purpose = "live_sharing"),
                ),
            ),
        )

        val result = repository().account() as AccountResult.Loaded

        assertEquals(
            AccountSummary(
                maskedPhone = "+91 ••••• ••001",
                role = "user",
                locale = "en",
                grantedPurposes = listOf("account_core", "live_sharing"),
            ),
            result.account,
        )
        // The full number from the server's answer is not in what the UI receives.
        assertFalse(result.toString().contains("0000000001"))
        assertEquals(listOf(GET_ME, GET_CONSENTS), api.routesCalled())
    }

    @Test
    fun `an account without a phone number has no masked phone`() = runTest {
        api.on(GET_ME, ok(ME_JSON.replace(""""+910000000001"""", "null"))).on(GET_CONSENTS, ok(consentsJson()))

        val result = repository().account() as AccountResult.Loaded

        assertEquals(null, result.account.maskedPhone)
        assertTrue(result.account.grantedPurposes.isEmpty())
    }

    @Test
    fun `account is unavailable when signed out, without any request`() = runTest {
        assertEquals(AccountResult.Unavailable(retryable = false), repository(signedIn = false).account())
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `account failures say whether trying again may help, and leave the session state alone`() = runTest {
        val repository = repository()

        api.on(GET_ME, problemResponse(503, "db_unavailable"))
        assertEquals(AccountResult.Unavailable(retryable = true), repository.account())

        api.on(GET_ME, problemResponse(500, "internal_error"))
        assertEquals(AccountResult.Unavailable(retryable = false), repository.account())

        api.on(GET_ME, ok(ME_JSON)).on(GET_CONSENTS, problemResponse(503, "db_unavailable"))
        assertEquals(AccountResult.Unavailable(retryable = true), repository.account())

        assertEquals(SessionState.Loading, repository.state.value)
    }

    @Test
    fun `no connection is retryable`() = runTest {
        val repository = repository()
        server.close()

        assertEquals(AccountResult.Unavailable(retryable = true), repository.account())
    }
}
