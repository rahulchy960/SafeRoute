// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import com.saferoute.app.core.auth.FAKE_ID_TOKEN
import com.saferoute.app.core.auth.FAKE_PHONE_E164
import com.saferoute.app.core.auth.FAKE_REFRESHED_ID_TOKEN
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

/**
 * Every outcome of the session state machine, against the app's real HTTP client and a local
 * fake server. Firebase is a fake gateway; the flags are in memory.
 */
class SessionRepositoryTest {

    private val server = MockWebServer()
    private val api = FakeApi()
    private var uiLocale = LOCALE_ENGLISH

    @Before
    fun setUp() {
        server.dispatcher = api
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private class Fixture(
        val repository: SessionRepository,
        val gateway: FakePhoneAuthGateway,
        val store: InMemorySessionStore,
    )

    private fun fixture(signedIn: Boolean, flags: SessionFlags = SessionFlags()): Fixture {
        val gateway = FakePhoneAuthGateway(signedIn)
        val store = InMemorySessionStore(flags)
        // The network layer's own token source; signed in or not follows the gateway.
        val tokens = FakeIdTokenProvider(
            token = if (signedIn) FAKE_ID_TOKEN else null,
            refreshed = if (signedIn) FAKE_REFRESHED_ID_TOKEN else null,
        )
        val meApi = TestApi(server, tokens).create<MeApi>()
        return Fixture(SessionRepository(gateway, store, meApi) { uiLocale }, gateway, store)
    }

    private fun accountExistsWithCurrentConsent() {
        api.on(GET_ME, ok(ME_JSON)).on(GET_CONSENTS, ok(consentsJson(consentJson())))
    }

    // --- Before sign-in: nothing is sent ---------------------------------------------------

    @Test
    fun `starts as Loading until the first check`() {
        assertEquals(SessionState.Loading, fixture(signedIn = false).repository.state.value)
    }

    @Test
    fun `a fresh install needs the age declaration, and no request is sent`() = runTest {
        val f = fixture(signedIn = false)
        f.repository.refresh()

        assertEquals(SessionState.NeedsAge, f.repository.state.value)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `the order is age, then consent, then phone, without any request`() = runTest {
        val f = fixture(signedIn = false)
        f.repository.refresh()

        f.repository.markWelcomeSeen()
        assertEquals(SessionState.NeedsAge, f.repository.state.value)

        f.repository.confirmAdult()
        assertEquals(SessionState.NeedsConsent, f.repository.state.value)

        f.repository.acceptNotice(LOCALE_BENGALI)
        assertEquals(SessionState.SignedOut, f.repository.state.value)

        assertEquals(
            SessionFlags(
                welcomeSeen = true,
                ageConfirmed = true,
                acceptedNoticeVersion = NOTICE_VERSION,
                acceptedNoticeLocale = LOCALE_BENGALI,
            ),
            f.store.current,
        )
        assertTrue(api.requests.isEmpty())
        assertTrue(f.gateway.calls.isEmpty())
    }

    @Test
    fun `under 18 blocks the app with one local flag and zero network or sign-in calls`() = runTest {
        val f = fixture(signedIn = false)
        f.repository.refresh()

        f.repository.declareUnder18()

        assertEquals(SessionState.Blocked(BlockReason.UNDER_18), f.repository.state.value)
        assertEquals(SessionFlags(under18 = true), f.store.current)
        assertEquals(1, f.store.writes)
        assertTrue(api.requests.isEmpty())
        assertTrue(f.gateway.calls.isEmpty())
        assertTrue(f.gateway.tokenRequests.isEmpty())
    }

    @Test
    fun `under 18 stays blocked after a restart, still without a request`() = runTest {
        val f = fixture(signedIn = false, flags = SessionFlags(under18 = true))
        f.repository.refresh()

        assertEquals(SessionState.Blocked(BlockReason.UNDER_18), f.repository.state.value)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `declining the notice stores and sends nothing`() = runTest {
        val f = fixture(signedIn = false, flags = SessionFlags(welcomeSeen = true, ageConfirmed = true))
        f.repository.refresh()
        val writesBefore = f.store.writes

        // Declining is "do nothing": there is no call to make. The state stays where it was.
        f.repository.refresh()

        assertEquals(SessionState.NeedsConsent, f.repository.state.value)
        assertEquals(writesBefore, f.store.writes)
        assertEquals(null, f.store.current.acceptedNoticeVersion)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a notice accepted for an older version must be accepted again`() = runTest {
        val old = ONBOARDED.copy(acceptedNoticeVersion = "2025-01-old")
        val f = fixture(signedIn = false, flags = old)
        f.repository.refresh()

        assertEquals(SessionState.NeedsConsent, f.repository.state.value)
    }

    @Test
    fun `process death resumes at the step the flags describe`() = runTest {
        val steps = listOf(
            SessionFlags() to SessionState.NeedsAge,
            SessionFlags(welcomeSeen = true) to SessionState.NeedsAge,
            SessionFlags(welcomeSeen = true, ageConfirmed = true) to SessionState.NeedsConsent,
            ONBOARDED to SessionState.SignedOut,
        )
        for ((flags, expected) in steps) {
            // A new repository over the same stored flags is what a restarted process sees.
            val f = fixture(signedIn = false, flags = flags)
            f.repository.refresh()
            assertEquals(flags.toString(), expected, f.repository.state.value)
        }
        assertTrue(api.requests.isEmpty())
    }

    // --- After sign-in ---------------------------------------------------------------------

    @Test
    fun `no account yet - bootstrap with age and consent, then Ready`() = runTest {
        api.on(GET_ME, problemResponse(403, "bootstrap_required"))
            .on(POST_BOOTSTRAP, ok(ME_JSON, code = 201))
        uiLocale = LOCALE_BENGALI
        val f = fixture(signedIn = true, flags = ONBOARDED)

        f.repository.refresh()

        assertEquals(SessionState.Ready, f.repository.state.value)
        assertEquals(listOf(GET_ME, POST_BOOTSTRAP), api.routesCalled())
        assertTrue(f.store.current.readyOnce)
    }

    @Test
    fun `the bootstrap body has the age declaration, notice, purposes and locale, never a phone number`() = runTest {
        api.on(GET_ME, problemResponse(403, "bootstrap_required"))
            .on(POST_BOOTSTRAP, ok(ME_JSON, code = 201))
        uiLocale = LOCALE_BENGALI
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(acceptedNoticeLocale = LOCALE_BENGALI))

        f.repository.refresh()

        val body = api.bodyOf(POST_BOOTSTRAP)
        assertEquals(
            """{"locale":"bn","consent":{"noticeVersion":"$NOTICE_VERSION","noticeLocale":"bn",""" +
                """"purposes":["account_core"],"ageConfirmed":true}}""",
            body,
        )
        assertFalse(body.contains("phone", ignoreCase = true))
        assertFalse(body.contains(FAKE_PHONE_E164))
        assertFalse(body.contains("+91"))
        assertFalse(body.contains("displayName"))
    }

    @Test
    fun `the UI locale and the notice locale are sent independently`() = runTest {
        api.on(GET_ME, problemResponse(403, "bootstrap_required"))
            .on(POST_BOOTSTRAP, ok(ME_JSON, code = 201))
        uiLocale = LOCALE_ENGLISH
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(acceptedNoticeLocale = LOCALE_BENGALI))

        f.repository.refresh()

        val body = api.bodyOf(POST_BOOTSTRAP)
        assertTrue(body.contains(""""locale":"en""""))
        assertTrue(body.contains(""""noticeLocale":"bn""""))
    }

    @Test
    fun `bootstrap_required without local age or consent goes back to that step, no bootstrap`() = runTest {
        api.on(GET_ME, problemResponse(403, "bootstrap_required"))

        val noAge = fixture(signedIn = true, flags = SessionFlags())
        noAge.repository.refresh()
        assertEquals(SessionState.NeedsAge, noAge.repository.state.value)

        val noConsent = fixture(signedIn = true, flags = SessionFlags(ageConfirmed = true))
        noConsent.repository.refresh()
        assertEquals(SessionState.NeedsConsent, noConsent.repository.state.value)

        assertFalse(POST_BOOTSTRAP in api.routesCalled())
    }

    @Test
    fun `resuming after bootstrap_required - accepting the notice creates the account`() = runTest {
        api.on(GET_ME, problemResponse(403, "bootstrap_required"))
            .on(POST_BOOTSTRAP, ok(ME_JSON, code = 201))
        val f = fixture(signedIn = true, flags = SessionFlags(ageConfirmed = true))
        f.repository.refresh()
        assertEquals(SessionState.NeedsConsent, f.repository.state.value)

        f.repository.acceptNotice(LOCALE_ENGLISH)

        assertEquals(SessionState.Ready, f.repository.state.value)
        assertEquals(1, api.routesCalled().count { it == POST_BOOTSTRAP })
    }

    @Test
    fun `an existing account with the current consent is Ready`() = runTest {
        accountExistsWithCurrentConsent()
        val f = fixture(signedIn = true, flags = ONBOARDED)

        f.repository.refresh()

        assertEquals(SessionState.Ready, f.repository.state.value)
        assertEquals(listOf(GET_ME, GET_CONSENTS), api.routesCalled())
        assertTrue(f.store.current.readyOnce)
        assertTrue(api.requests.all { it.authorization == "Bearer $FAKE_ID_TOKEN" })
    }

    @Test
    fun `consent missing, withdrawn or for an old notice needs consent again`() = runTest {
        val cases = listOf(
            consentsJson(),
            consentsJson(consentJson(purpose = "sos_alerts")),
            consentsJson(consentJson(status = "withdrawn")),
            consentsJson(consentJson(noticeVersion = "2025-01-old")),
            consentsJson(consentJson(status = "a-status-from-the-future")),
        )
        for (consents in cases) {
            api.on(GET_ME, ok(ME_JSON)).on(GET_CONSENTS, ok(consents))
            // Signed in on a phone that has not accepted the current notice (new phone, or the
            // notice changed).
            val f = fixture(signedIn = true, flags = SessionFlags(ageConfirmed = true))

            f.repository.refresh()

            assertEquals(consents, SessionState.NeedsConsent, f.repository.state.value)
            assertFalse(f.store.current.readyOnce)
        }
        assertFalse(PUT_ACCOUNT_CORE in api.routesCalled())
    }

    @Test
    fun `accepting the new notice sends PUT account_core and then is Ready`() = runTest {
        api.on(GET_ME, ok(ME_JSON))
            .on(GET_CONSENTS, ok(consentsJson(consentJson(noticeVersion = "2025-01-old"))))
            .on(PUT_ACCOUNT_CORE, ok(consentJson()))
        val f = fixture(signedIn = true, flags = SessionFlags(ageConfirmed = true))
        f.repository.refresh()
        assertEquals(SessionState.NeedsConsent, f.repository.state.value)

        f.repository.acceptNotice(LOCALE_BENGALI)

        assertEquals(SessionState.Ready, f.repository.state.value)
        assertEquals(
            """{"status":"granted","noticeVersion":"$NOTICE_VERSION","noticeLocale":"bn"}""",
            api.bodyOf(PUT_ACCOUNT_CORE),
        )
        assertTrue(f.store.current.readyOnce)
    }

    @Test
    fun `a notice accepted before the app was killed is sent on the next start`() = runTest {
        api.on(GET_ME, ok(ME_JSON))
            .on(GET_CONSENTS, ok(consentsJson()))
            .on(PUT_ACCOUNT_CORE, ok(consentJson()))
        val f = fixture(signedIn = true, flags = ONBOARDED)

        f.repository.refresh()

        assertEquals(SessionState.Ready, f.repository.state.value)
        assertEquals(listOf(GET_ME, GET_CONSENTS, PUT_ACCOUNT_CORE), api.routesCalled())
    }

    @Test
    fun `account_deleted blocks the app and signs out`() = runTest {
        api.on(GET_ME, problemResponse(403, "account_deleted"))
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(readyOnce = true))

        f.repository.refresh()

        assertEquals(SessionState.Blocked(BlockReason.ACCOUNT_DELETED), f.repository.state.value)
        assertEquals(listOf("signOut"), f.gateway.calls)
        assertEquals(null, f.gateway.currentUser)
        assertFalse(f.store.current.readyOnce)
    }

    @Test
    fun `account_deleted from bootstrap also blocks`() = runTest {
        api.on(GET_ME, problemResponse(403, "bootstrap_required"))
            .on(POST_BOOTSTRAP, problemResponse(403, "account_deleted"))
        val f = fixture(signedIn = true, flags = ONBOARDED)

        f.repository.refresh()

        assertEquals(SessionState.Blocked(BlockReason.ACCOUNT_DELETED), f.repository.state.value)
    }

    @Test
    fun `401 after the one token refresh signs out and keeps age and consent`() = runTest {
        api.on(GET_ME, unauthorizedResponse())
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(readyOnce = true))

        f.repository.refresh()

        assertEquals(SessionState.SignedOut, f.repository.state.value)
        // The network layer tried once more with a refreshed token before giving up.
        assertEquals(listOf(GET_ME, GET_ME), api.routesCalled())
        assertEquals("Bearer $FAKE_REFRESHED_ID_TOKEN", api.requests.last().authorization)
        assertEquals(listOf("signOut"), f.gateway.calls)
        assertEquals(ONBOARDED.copy(readyOnce = false), f.store.current)
    }

    // --- Offline and server trouble ---------------------------------------------------------

    @Test
    fun `503 on a first run is a retryable error, and retrying works`() = runTest {
        api.on(GET_ME, problemResponse(503, "db_unavailable"))
        val f = fixture(signedIn = true, flags = ONBOARDED)

        f.repository.refresh()
        assertEquals(SessionState.Error(retryable = true), f.repository.state.value)
        assertTrue(f.gateway.calls.isEmpty())

        accountExistsWithCurrentConsent()
        f.repository.refresh()
        assertEquals(SessionState.Ready, f.repository.state.value)
    }

    @Test
    fun `auth_unavailable is retryable, not a sign-out`() = runTest {
        api.on(GET_ME, problemResponse(503, "auth_unavailable"))
        val f = fixture(signedIn = true, flags = ONBOARDED)

        f.repository.refresh()

        assertEquals(SessionState.Error(retryable = true), f.repository.state.value)
        assertTrue(f.gateway.calls.isEmpty())
    }

    @Test
    fun `no connection on a first run is a retryable error`() = runTest {
        val f = fixture(signedIn = true, flags = ONBOARDED)
        server.close()

        f.repository.refresh()

        assertEquals(SessionState.Error(retryable = true), f.repository.state.value)
        assertTrue(f.gateway.calls.isEmpty())
        assertFalse(f.store.current.readyOnce)
    }

    @Test
    fun `no connection after being ready once opens Home`() = runTest {
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(readyOnce = true))
        server.close()

        f.repository.refresh()

        assertEquals(SessionState.Ready, f.repository.state.value)
        assertTrue(f.gateway.calls.isEmpty())
        assertTrue(f.store.current.readyOnce)
    }

    @Test
    fun `server trouble after being ready once keeps Home open`() = runTest {
        val responses = listOf(
            problemResponse(503, "db_unavailable"),
            problemResponse(503, "auth_unavailable"),
            problemResponse(500, "internal_error"),
            problemResponse(418, "a_code_from_the_future"),
            htmlResponse(502),
        )
        for (response in responses) {
            api.on(GET_ME, response)
            val f = fixture(signedIn = true, flags = ONBOARDED.copy(readyOnce = true))

            f.repository.refresh()

            assertEquals(SessionState.Ready, f.repository.state.value)
            assertTrue(f.gateway.calls.isEmpty())
        }
    }

    @Test
    fun `the consents check failing after being ready once keeps Home open`() = runTest {
        api.on(GET_ME, ok(ME_JSON)).on(GET_CONSENTS, problemResponse(503, "db_unavailable"))
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(readyOnce = true))

        f.repository.refresh()

        assertEquals(SessionState.Ready, f.repository.state.value)
    }

    @Test
    fun `an unknown problem code or a non-problem error is a generic error, never a crash`() = runTest {
        val responses = listOf(
            problemResponse(418, "a_code_from_the_future"),
            problemResponse(500, "internal_error"),
            problemResponse(403, "forbidden"),
            problemResponse(409, "phone_already_registered"),
            htmlResponse(502),
            ok("not json at all"),
            ok("""{"unexpected":"shape"}"""),
        )
        for (response in responses) {
            api.on(GET_ME, response)
            val f = fixture(signedIn = true, flags = ONBOARDED)

            f.repository.refresh()

            assertEquals(SessionState.Error(retryable = false), f.repository.state.value)
            assertTrue(f.gateway.calls.isEmpty())
        }
    }

    @Test
    fun `a failing bootstrap is an error and nothing is marked ready`() = runTest {
        val cases = listOf(
            problemResponse(503, "db_unavailable") to SessionState.Error(retryable = true),
            problemResponse(409, "phone_already_registered") to SessionState.Error(retryable = false),
            problemResponse(403, "consent_required") to SessionState.Error(retryable = false),
            problemResponse(403, "adult_required") to SessionState.Error(retryable = false),
            problemResponse(400, "validation_error") to SessionState.Error(retryable = false),
        )
        for ((response, expected) in cases) {
            api.on(GET_ME, problemResponse(403, "bootstrap_required")).on(POST_BOOTSTRAP, response)
            val f = fixture(signedIn = true, flags = ONBOARDED)

            f.repository.refresh()

            assertEquals(expected, f.repository.state.value)
            assertFalse(f.store.current.readyOnce)
        }
    }

    // --- Sign out ---------------------------------------------------------------------------

    @Test
    fun `sign out leaves Firebase, clears every flag and returns to the start of onboarding`() = runTest {
        accountExistsWithCurrentConsent()
        val f = fixture(signedIn = true, flags = ONBOARDED)
        f.repository.refresh()
        assertEquals(SessionState.Ready, f.repository.state.value)
        val requestsBefore = api.requests.size

        f.repository.signOut()

        assertEquals(SessionState.NeedsAge, f.repository.state.value)
        assertEquals(SessionFlags(), f.store.current)
        assertEquals(null, f.gateway.currentUser)
        assertEquals(requestsBefore, api.requests.size)
    }

    @Test
    fun `after sign out the app does not open offline as ready`() = runTest {
        val f = fixture(signedIn = true, flags = ONBOARDED.copy(readyOnce = true))
        f.repository.signOut()
        server.close()

        f.repository.refresh()

        assertEquals(SessionState.NeedsAge, f.repository.state.value)
    }
}
