// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a phone number and a name for an emergency contact. Plain JVM tests. */
class ContactRulesTest {

    @Test
    fun `indian mobile numbers are accepted in the ways people write them`() {
        val expected = "+919000010001"
        val inputs = listOf(
            "9000010001",
            "90000 10001",
            "90000-10001",
            "(90000) 10001",
            "09000010001",
            "919000010001",
            "+919000010001",
            "+91 90000 10001",
            "+91-90000-10001",
            "0091 90000 10001",
            "  +91 90000 10001  ",
        )
        for (input in inputs) assertEquals(input, expected, normaliseContactPhone(input))
        for (first in '6'..'9') assertEquals("+91${first}000010001", normaliseContactPhone("${first}000010001"))
    }

    @Test
    fun `landlines, short codes and broken numbers are rejected`() {
        val inputs = listOf(
            "",
            "   ",
            "112",
            "100",
            "12345",
            // Ten digits that do not start with 6 to 9 are not a mobile number.
            "5000010001",
            "1234567890",
            // A landline with its area code.
            "033 0000 0000",
            "+91 33 0000 0000",
            "900001000",
            "90000100011",
            "+91 90000 1000",
            "9000o10001",
            "abcdefghij",
            "+",
            "++919000010001",
        )
        for (input in inputs) assertNull(input, normaliseContactPhone(input))
    }

    @Test
    fun `other countries need an explicit plus and keep their digits`() {
        assertEquals("+10000000000", normaliseContactPhone("+1 (000) 000-0000"))
        assertEquals("+440000000000", normaliseContactPhone("+44 0000 000000"))
        // The shortest and the longest the server accepts: 7 and 15 digits.
        assertEquals("+1000000", normaliseContactPhone("+1000000"))
        assertEquals("+100000000000000", normaliseContactPhone("+100000000000000"))

        assertNull(normaliseContactPhone("+100000"))
        assertNull(normaliseContactPhone("+1000000000000000"))
        assertNull(normaliseContactPhone("+0000000000"))
        assertNull(normaliseContactPhone("+1 000 000 000x"))
        // Without a plus, a foreign number is read as Indian and fails.
        assertNull(normaliseContactPhone("1 000 000 0000"))
    }

    @Test
    fun `every accepted number has the shape the server checks`() {
        val server = Regex("""^\+[1-9][0-9]{6,14}$""")
        for (input in listOf("9000010001", "+1 000 000 0000", "0091 90000 10001", "+1000000")) {
            assertTrue(input, server.matches(normaliseContactPhone(input)!!))
        }
    }

    @Test
    fun `a name is trimmed, 1 to 80 characters, with no control characters`() {
        assertEquals("Test Contact", normaliseContactName("  Test Contact  "))
        assertEquals("x".repeat(CONTACT_NAME_MAX), normaliseContactName("x".repeat(CONTACT_NAME_MAX)))

        assertNull(normaliseContactName(""))
        assertNull(normaliseContactName("   "))
        assertNull(normaliseContactName("x".repeat(CONTACT_NAME_MAX + 1)))
        assertNull(normaliseContactName("Test\nContact"))
        assertNull(normaliseContactName("Test\tContact"))
    }

    @Test
    fun `a bengali name with its joiners is kept as typed`() {
        // The zero-width joiner is part of correct Bengali spelling, not a control character.
        val name = "র্‍যাব"
        assertEquals(name, normaliseContactName(name))
    }

    @Test
    fun `status is opted out first, then invited`() {
        assertEquals(ContactStatus.NotInvited, fakeContact(1).status)
        assertEquals(ContactStatus.Invited, fakeContact(1, invited = true).status)
        assertEquals(ContactStatus.OptedOut, fakeContact(1, invited = true, optedOut = true).status)
        assertEquals("EmergencyContact(hidden)", fakeContact(1).toString())
    }
}

/** When the phone's copy is fetched and when it is emptied. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsSessionSyncTest {

    private val repository = FakeContactsRepository().apply { set(fakeContact(1)) }

    /** The sync's "app scope" is the test's background scope, running at once. */
    private fun TestScope.syncFor(session: FakeSession) = ContactsSessionSync(
        session,
        repository,
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun `ready fetches the list once, and again after the next sign-in`() = runTest {
        val session = FakeSession(SessionState.Loading)
        syncFor(session).start()
        assertEquals(emptyList<String>(), repository.calls)

        session.setState(SessionState.Ready)
        assertEquals(listOf("refresh"), repository.calls)

        session.setState(SessionState.SignedOut)
        session.setState(SessionState.Ready)
        assertEquals(listOf("refresh", "clearLocal", "refresh"), repository.calls)
    }

    @Test
    fun `signing out, starting over and a blocked account empty the copy`() = runTest {
        val states = listOf(
            SessionState.SignedOut,
            SessionState.NeedsAge,
            SessionState.Blocked(BlockReason.ACCOUNT_DELETED),
            SessionState.Blocked(BlockReason.UNDER_18),
        )
        for (state in states) {
            repository.set(fakeContact(1))
            val session = FakeSession(SessionState.Ready)
            syncFor(session).start()

            session.setState(state)

            assertEquals(state.toString(), emptyList<EmergencyContact>(), repository.current)
        }
    }

    @Test
    fun `loading, an error and a newer notice leave the copy alone`() = runTest {
        val session = FakeSession(SessionState.Loading)
        syncFor(session).start()

        for (state in listOf(
            SessionState.Error(retryable = true),
            SessionState.Error(retryable = false),
            SessionState.NeedsConsent,
            SessionState.NeedsBootstrap,
            SessionState.Loading,
        )) {
            session.setState(state)
        }

        assertEquals(emptyList<String>(), repository.calls)
        assertEquals(1, repository.current.size)
    }

    @Test
    fun `a failed fetch after sign-in keeps what is on the phone`() = runTest {
        repository.failWith = ContactsError.NoConnection
        val session = FakeSession(SessionState.Ready)

        syncFor(session).start()

        assertEquals(listOf("refresh"), repository.calls)
        assertEquals(1, repository.current.size)
    }

    @Test
    fun `starting twice listens once`() = runTest {
        val session = FakeSession(SessionState.Ready)
        val sync = syncFor(session)

        sync.start()
        sync.start()

        assertEquals(listOf("refresh"), repository.calls)
    }
}

/**
 * Walls around the contacts code (ADR 0024). Plain JVM test that reads the source files; Gradle
 * runs unit tests with `android/app` as the working directory.
 */
class ContactsBoundaryTest {

    private fun kotlinFiles(vararg roots: String): List<File> = roots.flatMap { root ->
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun File.codeLines(): List<String> =
        readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }

    @Test
    fun `only core data uses room`() {
        val users = kotlinFiles("src/main", "src/debug", "src/release")
            .filter { file -> file.codeLines().any { it.contains("androidx.room") } }
            .map { it.invariantSeparatorsPath }
            .filterNot { it.contains("/core/data/") }

        assertEquals(emptyList<String>(), users)
    }

    @Test
    fun `what sos reads never touches the network`() {
        val offenders = listOf("ActiveSosContacts.kt", "local/ContactDao.kt", "local/ContactEntity.kt")
            .map { File("src/main/java/com/saferoute/app/core/data/$it") }
            .flatMap { file -> file.codeLines().map { file.name to it } }
            .filter { (_, line) -> listOf("core.network", "okhttp3", "retrofit2").any(line::contains) }

        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }

    @Test
    fun `contacts code never logs, never reads the address book and never sends a message`() {
        val forbidden = listOf(
            "android.util.Log",
            "println(",
            "SmsManager",
            "READ_CONTACTS",
            "SEND_SMS",
            "SharedPreferences",
            "datastore",
        )
        val offenders = kotlinFiles(
            "src/main/java/com/saferoute/app/feature/contacts",
            "src/main/java/com/saferoute/app/core/data",
        )
            .flatMap { file -> file.codeLines().map { file.name to it } }
            .filter { (_, line) -> forbidden.any(line::contains) }

        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }

    @Test
    fun `the manifest asks for no contacts or sms permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        for (permission in listOf("READ_CONTACTS", "WRITE_CONTACTS", "SEND_SMS", "RECEIVE_SMS", "READ_SMS")) {
            assertTrue(permission, !manifest.contains(permission))
        }
    }
}
