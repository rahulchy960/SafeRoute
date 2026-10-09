// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.data.RoomActiveSosContacts
import com.saferoute.app.core.network.ApiConfig
import com.saferoute.app.core.network.FIXED_CLOCK
import com.saferoute.app.core.network.FakeIdTokenProvider
import com.saferoute.app.core.network.RecordingSleeper
import com.saferoute.app.core.network.TEST_VERSION
import com.saferoute.app.core.network.di.newApiHttpClient
import com.saferoute.app.core.network.di.newApiRetrofit
import com.saferoute.app.core.network.generated.api.ContactsApi
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.problemResponse
import com.saferoute.app.core.session.FakeApi
import com.saferoute.app.core.session.consentJson
import com.saferoute.app.core.session.consentsJson
import com.saferoute.app.core.session.ok
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

private const val ID_1 = "00000000-0000-4000-8000-000000000001"
private const val ID_2 = "00000000-0000-4000-8000-000000000002"
private const val PHONE_1 = "+919000010001"
private const val PHONE_2 = "+919000010002"
private const val FAKE_TOKEN = "FAKE-TOKEN-0000000000AA"

private const val LIST = "GET /v1/contacts"
private const val CREATE = "POST /v1/contacts"
private const val CONSENTS = "GET /v1/me/consents"
private const val PUT_SOS = "PUT /v1/me/consents/sos_alerts"

/** A contact exactly as the server sends it (contract 0.9.0), with a field from the future. */
private fun contactJson(
    id: String = ID_1,
    name: String = "Test Contact 1",
    phone: String = PHONE_1,
    invitedAt: String? = null,
    optedOutAt: String? = null,
): String {
    fun time(value: String?) = value?.let { "\"$it\"" } ?: "null"
    return """{"id":"$id","name":"$name","phoneE164":"$phone","createdAt":"2026-10-09T09:00:00.000Z",""" +
        """"invitedAt":${time(invitedAt)},"optedOutAt":${time(optedOutAt)},"futureField":true}"""
}

private fun listJson(vararg contacts: String) = """{"items":[${contacts.joinToString(",")}],"maxContacts":5}"""

private fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

/**
 * The real repository with the app's real HTTP client and JSON decoding against a local fake
 * server, and a real SQLite database in memory. Only the server and the phone are fake.
 */
@RunWith(AndroidJUnit4::class)
class ApiContactsRepositoryTest {

    private val server = MockWebServer()
    private val api = FakeApi()
    private val database = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val dao = database.contactDao()
    private val preferences = FakeContactsPreferences()
    private lateinit var config: ApiConfig
    private lateinit var repository: ApiContactsRepository

    @Before
    fun setUp() {
        ShadowLog.clear()
        server.dispatcher = api
        server.start()
        // Debug logging ON, with the real Logcat logger: what a debug build writes is checked.
        config = ApiConfig(server.url("/"), isConfigured = true, TEST_VERSION, debugLogging = true)
        repository = repositoryFor(config)
    }

    private fun repositoryFor(config: ApiConfig): ApiContactsRepository {
        val client = newApiHttpClient(config, FakeIdTokenProvider("fake-id-token"), FIXED_CLOCK, RecordingSleeper())
        val retrofit = newApiRetrofit(config) { client }
        return ApiContactsRepository(
            retrofit.create(ContactsApi::class.java),
            retrofit.create(MeApi::class.java),
            dao,
            config,
            preferences,
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    private suspend fun local() = repository.contacts.first()

    private suspend fun seed(vararg contacts: String) {
        api.on(LIST, ok(listJson(*contacts)))
        assertEquals(ContactsResult.Ok(Unit), repository.refresh())
    }

    // Sync -------------------------------------------------------------------------------

    @Test
    fun `the server's list replaces the copy on the phone`() = runTest {
        seed(contactJson(), contactJson(ID_2, "Test Contact 2", PHONE_2))
        assertEquals(listOf(ID_1, ID_2), local().map { it.id })

        // The server wins: a contact it no longer has disappears, a changed one changes.
        seed(contactJson(ID_2, "Renamed Elsewhere", PHONE_2, invitedAt = "2026-10-09T10:00:00.000Z"))

        val only = local().single()
        assertEquals("Renamed Elsewhere", only.name)
        assertEquals(ContactStatus.Invited, only.status)
    }

    @Test
    fun `a failed fetch never deletes or changes what is on the phone`() = runTest {
        seed(contactJson(), contactJson(ID_2, "Test Contact 2", PHONE_2))
        val before = local()

        for (failure in listOf(problemResponse(503, "db_unavailable"), problemResponse(500, "internal_error"))) {
            api.on(LIST, failure)
            assertTrue(repository.refresh() is ContactsResult.Failed)
            assertEquals(before, local())
        }

        server.close()
        assertEquals(ContactsResult.Failed(ContactsError.NoConnection), repository.refresh())
        assertEquals(before, local())
    }

    @Test
    fun `reading works with no server at all, and so does the sos list`() = runTest {
        seed(contactJson(), contactJson(ID_2, "Test Contact 2", PHONE_2, optedOutAt = "2026-10-09T11:00:00.000Z"))
        server.close()

        assertEquals(listOf(ContactStatus.NotInvited, ContactStatus.OptedOut), local().map { it.status })
        // Failure matrix: the opted-out contact is not among those an SOS may alert.
        assertEquals(listOf(PHONE_1), RoomActiveSosContacts(dao).current().map { it.phoneE164 })
    }

    @Test
    fun `an empty list from the server empties the copy`() = runTest {
        seed(contactJson())
        seed()

        assertEquals(emptyList<EmergencyContact>(), local())
    }

    // Changes need the network -----------------------------------------------------------

    @Test
    fun `every change fails with no connection and leaves the copy alone`() = runTest {
        seed(contactJson())
        val before = local()
        server.close()
        val offline = ContactsResult.Failed(ContactsError.NoConnection)

        assertEquals(offline, repository.add("Test Contact 2", PHONE_2))
        assertEquals(offline, repository.rename(ID_1, "Other"))
        assertEquals(offline, repository.remove(ID_1))
        assertEquals(offline, repository.createInvite(ID_1))
        assertEquals(offline, repository.confirmInvite(ID_1))
        assertEquals(offline, repository.hasConsent())
        assertEquals(offline, repository.grantConsent("en"))
        assertEquals(offline, repository.withdrawConsent("en"))

        assertEquals(before, local())
    }

    @Test
    fun `add sends name and phone in the body and stores the server's answer at once`() = runTest {
        api.on(CREATE, ok(contactJson(), code = 201))

        val result = repository.add("Test Contact 1", PHONE_1)

        assertEquals(ID_1, (result as ContactsResult.Ok).value.id)
        assertEquals("""{"name":"Test Contact 1","phone":"$PHONE_1"}""", api.bodyOf(CREATE))
        // No second request was needed for the copy to have it.
        assertEquals(listOf(CREATE), api.routesCalled())
        assertEquals(listOf(ID_1), local().map { it.id })
    }

    @Test
    fun `the calm answers of add map to their own errors`() = runTest {
        val expected = mapOf(
            problemResponse(403, "consent_required") to ContactsError.ConsentRequired,
            problemResponse(409, "contact_limit_reached") to ContactsError.LimitReached,
            problemResponse(409, "contact_opted_out") to ContactsError.OptedOut,
            problemResponse(409, "invalid_contact") to ContactsError.OwnNumber,
            problemResponse(400, "validation_error") to ContactsError.Invalid,
            problemResponse(503, "db_unavailable") to ContactsError.Unavailable,
            problemResponse(409, "a_code_from_the_future") to ContactsError.Unexpected,
        )
        for ((response, error) in expected) {
            api.on(CREATE, response)
            assertEquals(ContactsResult.Failed(error), repository.add("Test Contact 1", PHONE_1))
        }
        assertEquals(emptyList<EmergencyContact>(), local())
    }

    @Test
    fun `rate limited carries the wait when the server names one`() = runTest {
        val limited = MockResponse.Builder()
            .code(429)
            .addHeader("Content-Type", "application/problem+json")
            .addHeader("Retry-After", "42")
            .body("""{"type":"about:blank","title":"T","status":429,"detail":"D","code":"rate_limited","requestId":"r"}""")
            .build()
        api.on(CREATE, limited)
        assertEquals(ContactsResult.Failed(ContactsError.RateLimited(42)), repository.add("Test Contact 1", PHONE_1))

        // The tenth-link limit has no Retry-After: waiting does not help.
        api.on("POST /v1/contacts/$ID_1/invite", problemResponse(429, "rate_limited"))
        assertEquals(ContactsResult.Failed(ContactsError.RateLimited(null)), repository.createInvite(ID_1))
    }

    @Test
    fun `contact_exists refetches, so a retry after a lost answer finds the contact`() = runTest {
        // The first try reached the server but its answer was lost; the retry gets 409.
        api.on(CREATE, problemResponse(409, "contact_exists")).on(LIST, ok(listJson(contactJson())))

        val result = repository.add("Test Contact 1", PHONE_1)

        assertEquals(ContactsResult.Failed(ContactsError.AlreadyExists), result)
        assertEquals(listOf(CREATE, LIST), api.routesCalled())
        assertEquals(PHONE_1, local().single().phoneE164)
    }

    @Test
    fun `rename stores the answer, remove deletes, and neither needs a refetch`() = runTest {
        seed(contactJson(), contactJson(ID_2, "Test Contact 2", PHONE_2))
        api.on("PATCH /v1/contacts/$ID_1", ok(contactJson(name = "New Name")))
        api.on("DELETE /v1/contacts/$ID_2", noContent())

        assertEquals("New Name", (repository.rename(ID_1, "New Name") as ContactsResult.Ok).value.name)
        assertEquals("""{"name":"New Name"}""", api.bodyOf("PATCH /v1/contacts/$ID_1"))
        assertEquals(ContactsResult.Ok(Unit), repository.remove(ID_2))

        assertEquals(listOf(ID_1 to "New Name"), local().map { it.id to it.name })
        assertEquals(1, api.routesCalled().count { it == LIST })
    }

    @Test
    fun `a contact the server no longer has is dropped from the copy`() = runTest {
        seed(contactJson())
        // FakeApi answers 404 not_found for a route nobody defined.
        assertEquals(ContactsResult.Failed(ContactsError.NotFound), repository.rename(ID_1, "Other"))

        assertEquals(emptyList<EmergencyContact>(), local())
        // An id that is not a UUID never reaches the server.
        val calls = api.routesCalled().size
        assertEquals(ContactsResult.Failed(ContactsError.NotFound), repository.remove("not-a-uuid"))
        assertEquals(calls, api.routesCalled().size)
    }

    // Invite -----------------------------------------------------------------------------

    @Test
    fun `the invite link is the api address, slash c, and the token as the fragment`() = runTest {
        api.on("POST /v1/contacts/$ID_1/invite", ok("""{"optOutToken":"$FAKE_TOKEN"}"""))

        val link = (repository.createInvite(ID_1) as ContactsResult.Ok).value

        assertEquals("${server.url("/")}c#$FAKE_TOKEN", link.url)
        val parsed = link.url.toHttpUrl()
        // What a browser requests is /c with no query: the token is not part of it.
        assertEquals("/c", parsed.encodedPath)
        assertNull(parsed.query)
        assertEquals(FAKE_TOKEN, parsed.fragment)
        // The request for the token carried no body and no token of any kind in its URL.
        assertEquals("", api.bodyOf("POST /v1/contacts/$ID_1/invite"))
    }

    @Test
    fun `the link and the result never print the token`() = runTest {
        api.on("POST /v1/contacts/$ID_1/invite", ok("""{"optOutToken":"$FAKE_TOKEN"}"""))

        val result = repository.createInvite(ID_1)

        assertFalse(result.toString().contains(FAKE_TOKEN))
        assertFalse((result as ContactsResult.Ok).value.toString().contains(FAKE_TOKEN))
        assertEquals("https://example.invalid/c#x", inviteLink(config.copy(baseUrl = "https://example.invalid/".toHttpUrl()), "x").url)
    }

    @Test
    fun `no token is asked for when the build has no api address`() = runTest {
        val unconfigured = repositoryFor(config.copy(isConfigured = false))

        assertEquals(ContactsResult.Failed(ContactsError.Unavailable), unconfigured.createInvite(ID_1))
        assertEquals(emptyList<String>(), api.routesCalled())
    }

    @Test
    fun `an opted-out contact cannot be invited, and the copy learns it`() = runTest {
        seed(contactJson())
        api.on("POST /v1/contacts/$ID_1/invite", problemResponse(409, "contact_opted_out"))
        api.on(LIST, ok(listJson(contactJson(optedOutAt = "2026-10-09T11:00:00.000Z"))))

        assertEquals(ContactsResult.Failed(ContactsError.OptedOut), repository.createInvite(ID_1))

        assertEquals(ContactStatus.OptedOut, local().single().status)
        assertEquals(emptyList<String>(), RoomActiveSosContacts(dao).current().map { it.phoneE164 })
    }

    @Test
    fun `confirm is a 204 and brings the invited time with the next list`() = runTest {
        seed(contactJson())
        api.on("POST /v1/contacts/$ID_1/invite/confirm", noContent())
        api.on(LIST, ok(listJson(contactJson(invitedAt = "2026-10-09T10:00:00.000Z"))))

        assertEquals(ContactsResult.Ok(Unit), repository.confirmInvite(ID_1))

        assertEquals(ContactStatus.Invited, local().single().status)
    }

    // Consent ----------------------------------------------------------------------------

    @Test
    fun `consent counts only when it is granted for the notice the app shows today`() = runTest {
        val current = SOS_ALERTS_NOTICE_VERSION
        api.on(CONSENTS, ok(consentsJson(consentJson())))
        assertEquals(ContactsResult.Ok(false), repository.hasConsent())

        api.on(CONSENTS, ok(consentsJson(consentJson(), consentJson("sos_alerts", "withdrawn", current))))
        assertEquals(ContactsResult.Ok(false), repository.hasConsent())

        // Granted, but for the older notice that did not describe the alerts: asked again.
        api.on(CONSENTS, ok(consentsJson(consentJson(), consentJson("sos_alerts", "granted", "2026-10-alerts-draft1"))))
        assertEquals(ContactsResult.Ok(false), repository.hasConsent())
        assertNull(preferences.alertsNoticeVersion.value)

        api.on(CONSENTS, ok(consentsJson(consentJson(), consentJson("sos_alerts", "granted", current))))
        assertEquals(ContactsResult.Ok(true), repository.hasConsent())
        assertEquals("the phone now knows it without the network", current, preferences.alertsNoticeVersion.value)
    }

    @Test
    fun `the phone's note of the consent follows the server, and a failed check changes nothing`() = runTest {
        val current = SOS_ALERTS_NOTICE_VERSION
        api.on(CONSENTS, ok(consentsJson(consentJson("sos_alerts", "granted", current))))
        repository.hasConsent()
        assertEquals(current, preferences.alertsNoticeVersion.value)

        // No answer: neither given nor taken.
        api.on(CONSENTS, problemResponse(503, "db_unavailable"))
        assertEquals(ContactsResult.Failed(ContactsError.Unavailable), repository.hasConsent())
        assertEquals(current, preferences.alertsNoticeVersion.value)

        // Withdrawn elsewhere (another phone): the next check takes it away here too.
        api.on(CONSENTS, ok(consentsJson(consentJson("sos_alerts", "withdrawn", current))))
        repository.hasConsent()
        assertNull(preferences.alertsNoticeVersion.value)
    }

    @Test
    fun `granting notes the consent on the phone only after the server recorded it`() = runTest {
        api.on(PUT_SOS, problemResponse(503, "db_unavailable"))
        repository.grantConsent("en")
        assertNull(preferences.alertsNoticeVersion.value)

        api.on(PUT_SOS, ok(consentJson("sos_alerts", "granted", SOS_ALERTS_NOTICE_VERSION)))
        repository.grantConsent("en")
        assertEquals(SOS_ALERTS_NOTICE_VERSION, preferences.alertsNoticeVersion.value)
    }

    @Test
    fun `withdrawing and signing out take the note of the consent away`() = runTest {
        preferences.setAlertsNoticeVersion(SOS_ALERTS_NOTICE_VERSION)
        api.on(PUT_SOS, ok(consentJson("sos_alerts", "withdrawn")))
        repository.withdrawConsent("en")
        assertNull(preferences.alertsNoticeVersion.value)

        preferences.setAlertsNoticeVersion(SOS_ALERTS_NOTICE_VERSION)
        repository.clearLocal()
        assertNull(preferences.alertsNoticeVersion.value)
    }

    @Test
    fun `granting sends the notice version and the language it was read in`() = runTest {
        api.on(PUT_SOS, ok(consentJson("sos_alerts")))

        assertEquals(ContactsResult.Ok(Unit), repository.grantConsent("bn"))

        assertEquals(
            """{"status":"granted","noticeVersion":"$SOS_ALERTS_NOTICE_VERSION","noticeLocale":"bn"}""",
            api.bodyOf(PUT_SOS),
        )
    }

    @Test
    fun `withdrawing empties the copy, but only after the server confirmed`() = runTest {
        seed(contactJson(), contactJson(ID_2, "Test Contact 2", PHONE_2))

        api.on(PUT_SOS, problemResponse(503, "db_unavailable"))
        assertEquals(ContactsResult.Failed(ContactsError.Unavailable), repository.withdrawConsent("en"))
        assertEquals(2, local().size)

        api.on(PUT_SOS, ok(consentJson("sos_alerts", "withdrawn")))
        assertEquals(ContactsResult.Ok(Unit), repository.withdrawConsent("en"))
        assertTrue(api.bodyOf(PUT_SOS).contains(""""status":"withdrawn""""))
        assertEquals(emptyList<EmergencyContact>(), local())
        assertEquals(emptyList<String>(), RoomActiveSosContacts(dao).current().map { it.phoneE164 })
    }

    // Sign-out ---------------------------------------------------------------------------

    @Test
    fun `clearLocal empties the copy without calling the server`() = runTest {
        seed(contactJson())
        val calls = api.routesCalled().size

        repository.clearLocal()

        assertEquals(emptyList<EmergencyContact>(), local())
        assertEquals(calls, api.routesCalled().size)
    }

    @Test
    fun `a list that arrives after the copy was emptied is not written`() = runTest {
        // The fetch is under way when the user signs out: its answer belongs to nobody now.
        val slow = MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(listJson(contactJson()))
            .headersDelay(300, TimeUnit.MILLISECONDS)
            .build()
        api.on(LIST, slow)

        val fetch = CoroutineScope(Dispatchers.IO).launch { repository.refresh() }
        while (api.routesCalled().isEmpty()) Thread.sleep(5)
        repository.clearLocal()
        fetch.join()

        assertEquals(emptyList<EmergencyContact>(), local())
    }

    // Logs -------------------------------------------------------------------------------

    @Test
    fun `the whole flow writes no name, number or token to the log`() = runTest {
        api.on(CONSENTS, ok(consentsJson(consentJson("sos_alerts"))))
            .on(PUT_SOS, ok(consentJson("sos_alerts")))
            .on(CREATE, ok(contactJson(), code = 201))
            .on(LIST, ok(listJson(contactJson())))
            .on("PATCH /v1/contacts/$ID_1", ok(contactJson(name = "Renamed Person")))
            .on("POST /v1/contacts/$ID_1/invite", ok("""{"optOutToken":"$FAKE_TOKEN"}"""))
            .on("POST /v1/contacts/$ID_1/invite/confirm", noContent())
            .on("DELETE /v1/contacts/$ID_1", noContent())

        repository.hasConsent()
        repository.grantConsent("en")
        val added = repository.add("Test Contact 1", PHONE_1)
        repository.refresh()
        repository.rename(ID_1, "Renamed Person")
        val invite = repository.createInvite(ID_1)
        repository.confirmInvite(ID_1)
        android.util.Log.d("ContactsTest", "results: $added $invite ${local()}")
        repository.remove(ID_1)
        repository.withdrawConsent("en")

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable ?: ""}" }
        assertTrue("the debug logger must have written its lines", logged.contains("/v1/contacts"))
        for (secret in listOf("Test Contact", "Renamed Person", PHONE_1, "9000010001", FAKE_TOKEN, "optOutToken", "fake-id-token")) {
            assertFalse(secret, logged.contains(secret))
        }
    }
}
