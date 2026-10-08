// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import androidx.lifecycle.SavedStateHandle
import com.saferoute.app.core.session.AppLocale
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val PHONE = "+919000010009"

/**
 * The ViewModels of the contacts screens against the fake repository. Plain JVM tests: the
 * "main thread" is a test dispatcher that runs everything at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelsTest {

    private val repository = FakeContactsRepository()
    private val bengali = AppLocale { "bn" }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A StateFlow built with `stateIn` only works while somebody reads it. */
    private fun <T> TestScope.read(flow: StateFlow<T>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
    }

    private fun handle(id: String) = SavedStateHandle(mapOf(CONTACT_ID_ARGUMENT to id))

    // Consent ----------------------------------------------------------------------------

    @Test
    fun `nothing asks about consent until the add screen opens`() {
        ContactsViewModel(repository, bengali)
        ContactsCardViewModel(repository, FakeContactsPreferences(), Clock.systemUTC())

        assertFalse(repository.calls.contains("hasConsent"))
        assertFalse(repository.calls.any { it.startsWith("grantConsent") })
    }

    @Test
    fun `without consent on record the notice comes first, and agreeing records it`() {
        val viewModel = AddContactViewModel(repository, bengali)
        assertEquals(AddContactUiState.Notice(), viewModel.state.value)

        viewModel.onAgree()

        assertTrue(viewModel.state.value is AddContactUiState.Form)
        // The language the app is shown in goes with the consent.
        assertEquals(listOf("hasConsent", "grantConsent(bn)"), repository.calls)
    }

    @Test
    fun `leaving the notice without agreeing sends nothing`() {
        AddContactViewModel(repository, bengali)
        // "Not now" is the back arrow: the screen closes and no function is called.

        assertEquals(listOf("hasConsent"), repository.calls)
        assertFalse(repository.consentGranted)
    }

    @Test
    fun `with consent on record the form opens at once`() {
        repository.consentGranted = true

        val viewModel = AddContactViewModel(repository, bengali)

        assertEquals(AddContactUiState.Form(), viewModel.state.value)
        assertEquals(listOf("hasConsent"), repository.calls)
    }

    @Test
    fun `a failed check says so and can be tried again`() {
        repository.failWith = ContactsError.NoConnection
        val viewModel = AddContactViewModel(repository, bengali)
        assertEquals(AddContactUiState.CheckFailed(ContactsError.NoConnection), viewModel.state.value)

        repository.failWith = null
        viewModel.check()

        assertEquals(AddContactUiState.Notice(), viewModel.state.value)
    }

    @Test
    fun `agreeing without a connection stays on the notice with the reason`() {
        val viewModel = AddContactViewModel(repository, bengali)
        repository.failWith = ContactsError.NoConnection

        viewModel.onAgree()

        assertEquals(AddContactUiState.Notice(error = ContactsError.NoConnection), viewModel.state.value)
        assertFalse(repository.consentGranted)
    }

    // Adding -----------------------------------------------------------------------------

    private fun formViewModel(): AddContactViewModel {
        repository.consentGranted = true
        return AddContactViewModel(repository, bengali)
    }

    private fun AddContactViewModel.form() = state.value as AddContactUiState.Form

    @Test
    fun `a name and a number as people type them are saved normalised`() {
        val viewModel = formViewModel()
        viewModel.onNameChange("  Test Contact 9 ")
        viewModel.onPhoneChange("090000 10009")

        viewModel.onSave()

        val saved = viewModel.state.value as AddContactUiState.Saved
        val contact = repository.current.single()
        assertEquals(contact.id, saved.contactId)
        assertEquals("Test Contact 9" to PHONE, contact.name to contact.phoneE164)
    }

    @Test
    fun `an empty name or a landline never reaches the server`() {
        val viewModel = formViewModel()
        viewModel.onPhoneChange("033 0000 0000")

        viewModel.onSave()

        assertTrue(viewModel.form().nameInvalid)
        assertTrue(viewModel.form().phoneInvalid)
        assertFalse(repository.calls.contains("add"))

        // Typing again clears the mark on that field only.
        viewModel.onNameChange("Test Contact 9")
        assertFalse(viewModel.form().nameInvalid)
        assertTrue(viewModel.form().phoneInvalid)
    }

    @Test
    fun `the picker fills the form and the user still presses save`() {
        val viewModel = formViewModel()

        viewModel.onPicked(PickedContact("Test Contact 9", "90000 10009"))

        assertEquals("Test Contact 9" to "90000 10009", viewModel.form().name to viewModel.form().phone)
        assertFalse(repository.calls.contains("add"))

        viewModel.onPickProblem(PickProblem.Unreadable)
        assertEquals(PickProblem.Unreadable, viewModel.form().pickProblem)
    }

    @Test
    fun `the calm answers stay on the form with what was typed`() {
        for (error in listOf(
            ContactsError.OptedOut,
            ContactsError.OwnNumber,
            ContactsError.LimitReached,
            ContactsError.AlreadyExists,
            ContactsError.RateLimited(30),
            ContactsError.Unavailable,
        )) {
            val viewModel = formViewModel()
            viewModel.onNameChange("Test Contact 9")
            viewModel.onPhoneChange(PHONE)
            repository.failWith = error

            viewModel.onSave()

            assertEquals(error, viewModel.form().error)
            assertEquals("Test Contact 9", viewModel.form().name)
            assertFalse(viewModel.form().saving)
            repository.failWith = null
        }
    }

    @Test
    fun `at five contacts the sixth is refused with the limit`() {
        val viewModel = formViewModel()
        repository.set(*(1..5).map { fakeContact(it) }.toTypedArray())
        viewModel.onNameChange("Test Contact 9")
        viewModel.onPhoneChange(PHONE)

        viewModel.onSave()

        assertEquals(ContactsError.LimitReached, viewModel.form().error)
        assertEquals(5, repository.current.size)
    }

    @Test
    fun `already exists after a lost answer is the contact this form created`() {
        val viewModel = formViewModel()
        viewModel.onNameChange("Test Contact 9")
        viewModel.onPhoneChange(PHONE)

        // First try: no connection, as far as the phone can tell.
        repository.failWith = ContactsError.NoConnection
        viewModel.onSave()
        assertEquals(ContactsError.NoConnection, viewModel.form().error)

        // But it had reached the server. The retry finds the number there.
        repository.failWith = null
        val created = fakeContact(9).copy(phoneE164 = PHONE)
        repository.set(created)
        viewModel.onSave()

        assertEquals(AddContactUiState.Saved(created.id), viewModel.state.value)
    }

    @Test
    fun `already exists on a first try is told as it is`() {
        val viewModel = formViewModel()
        repository.set(fakeContact(9).copy(phoneE164 = PHONE))
        viewModel.onNameChange("Somebody Else")
        viewModel.onPhoneChange(PHONE)

        viewModel.onSave()

        assertEquals(ContactsError.AlreadyExists, viewModel.form().error)
    }

    @Test
    fun `consent withdrawn since the check brings the notice back`() {
        val viewModel = formViewModel()
        viewModel.onNameChange("Test Contact 9")
        viewModel.onPhoneChange(PHONE)
        repository.consentGranted = false

        viewModel.onSave()

        assertEquals(AddContactUiState.Notice(), viewModel.state.value)
    }

    // Inviting ---------------------------------------------------------------------------

    private fun inviteViewModel(contact: EmergencyContact = fakeContact(1)): InviteViewModel {
        repository.set(contact)
        return InviteViewModel(repository, handle(contact.id))
    }

    @Test
    fun `write asks for a link, hands it over once, and forgets it`() {
        val viewModel = inviteViewModel()
        assertEquals("Test Contact 1", viewModel.state.value.contactName)

        viewModel.onWrite()

        val sms = viewModel.state.value.smsToOpen!!
        assertEquals(fakeContact(1).phoneE164, sms.phoneE164)
        assertEquals(FAKE_INVITE_URL, sms.link.url)
        // Not in anything that could be printed.
        assertFalse("$sms ${viewModel.state.value}".contains("FAKE-TOKEN"))

        viewModel.onSmsOpened(opened = true)

        assertNull(viewModel.state.value.smsToOpen)
        assertEquals(InviteStep.AskSent, viewModel.state.value.step)
        // Opening the SMS app is not "invited".
        assertFalse(repository.calls.contains("confirmInvite"))
    }

    @Test
    fun `yes records the invite, later records nothing`() {
        val yes = inviteViewModel()
        yes.onWrite()
        yes.onSmsOpened(opened = true)
        yes.onSent()
        assertEquals(InviteStep.Done, yes.state.value.step)
        assertEquals(ContactStatus.Invited, repository.current.single().status)

        repository.calls.clear()
        val later = inviteViewModel()
        later.onWrite()
        later.onSmsOpened(opened = true)
        later.onLater()
        assertEquals(InviteStep.Done, later.state.value.step)
        assertFalse(repository.calls.contains("confirmInvite"))
        assertEquals(ContactStatus.NotInvited, repository.current.single().status)
    }

    @Test
    fun `a phone without an sms app says so and records nothing`() {
        val viewModel = inviteViewModel()
        viewModel.onWrite()

        viewModel.onSmsOpened(opened = false)

        assertEquals(InviteStep.NoSmsApp, viewModel.state.value.step)
        assertNull(viewModel.state.value.smsToOpen)
        assertFalse(repository.calls.contains("confirmInvite"))
    }

    @Test
    fun `an opted-out contact gets no link, and the screen says why`() {
        val viewModel = inviteViewModel(fakeContact(1, optedOut = true))

        viewModel.onWrite()

        assertNull(viewModel.state.value.smsToOpen)
        assertEquals(InviteStep.Intro, viewModel.state.value.step)
        assertEquals(ContactsError.OptedOut, viewModel.state.value.error)
    }

    @Test
    fun `a failed confirm keeps the question and can be answered again`() {
        val viewModel = inviteViewModel()
        viewModel.onWrite()
        viewModel.onSmsOpened(opened = true)
        repository.failWith = ContactsError.NoConnection

        viewModel.onSent()

        assertEquals(InviteStep.AskSent, viewModel.state.value.step)
        assertEquals(ContactsError.NoConnection, viewModel.state.value.error)

        repository.failWith = null
        viewModel.onSent()
        assertEquals(InviteStep.Done, viewModel.state.value.step)
    }

    @Test
    fun `an invite for a contact that is gone closes at once`() {
        val viewModel = InviteViewModel(repository, handle("00000000-0000-4000-8000-000000000099"))

        assertTrue(viewModel.state.value.missing)
        viewModel.onWrite()
        assertFalse(repository.calls.contains("createInvite"))
    }

    // One contact ------------------------------------------------------------------------

    @Test
    fun `rename checks the name first, then stores the trimmed one`() = runTest {
        repository.set(fakeContact(1))
        val viewModel = ContactDetailViewModel(repository, handle(fakeContact(1).id))
        read(viewModel.state)

        assertFalse(viewModel.onRename("   "))
        assertFalse(repository.calls.contains("rename"))

        assertTrue(viewModel.onRename("  New Name "))
        assertEquals("New Name", viewModel.state.value.contact?.name)
    }

    @Test
    fun `remove reports it, and a failure keeps the contact`() = runTest {
        repository.set(fakeContact(1))
        val viewModel = ContactDetailViewModel(repository, handle(fakeContact(1).id))
        read(viewModel.state)

        repository.failWith = ContactsError.NoConnection
        viewModel.onRemoveConfirmed()
        assertEquals(ContactsError.NoConnection, viewModel.state.value.error)
        assertFalse(viewModel.state.value.removed)
        assertEquals(1, repository.current.size)

        repository.failWith = null
        viewModel.onRemoveConfirmed()
        assertTrue(viewModel.state.value.removed)
        assertEquals(0, repository.current.size)
    }

    // The list ---------------------------------------------------------------------------

    @Test
    fun `the list shows the copy on the phone when the fetch finds no connection`() = runTest {
        repository.set(fakeContact(1), fakeContact(2, optedOut = true))
        repository.failWith = ContactsError.NoConnection

        val viewModel = ContactsViewModel(repository, bengali)
        read(viewModel.state)

        val state = viewModel.state.value
        assertEquals(2, state.contacts?.size)
        assertTrue(state.offline)
        // The fetch nobody asked for does not complain; the offline line says enough.
        assertNull(state.error)
        assertFalse(state.refreshing)
    }

    @Test
    fun `refresh by hand reports a server failure, and add is off at five`() = runTest {
        repository.set(*(1..5).map { fakeContact(it) }.toTypedArray())
        val viewModel = ContactsViewModel(repository, bengali)
        read(viewModel.state)
        assertFalse(viewModel.state.value.canAdd)

        repository.failWith = ContactsError.Unavailable
        viewModel.onRefresh()

        assertEquals(ContactsError.Unavailable, viewModel.state.value.error)
        assertFalse(viewModel.state.value.offline)
        viewModel.onMessageShown()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `stopping withdraws the consent and empties the list`() = runTest {
        repository.consentGranted = true
        repository.set(fakeContact(1), fakeContact(2))
        val viewModel = ContactsViewModel(repository, bengali)
        read(viewModel.state)

        viewModel.onStopConfirmed()

        assertTrue(viewModel.state.value.stopped)
        assertEquals(emptyList<EmergencyContact>(), viewModel.state.value.contacts)
        assertTrue(repository.calls.contains("withdrawConsent(bn)"))
        assertFalse(repository.consentGranted)
    }

    @Test
    fun `stopping without a connection changes nothing and says so`() = runTest {
        repository.set(fakeContact(1))
        val viewModel = ContactsViewModel(repository, bengali)
        read(viewModel.state)
        repository.failWith = ContactsError.NoConnection

        viewModel.onStopConfirmed()

        assertEquals(ContactsError.NoConnection, viewModel.state.value.error)
        assertFalse(viewModel.state.value.stopped)
        assertEquals(1, viewModel.state.value.contacts?.size)
    }

    // The card on Home -------------------------------------------------------------------

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @Test
    fun `the card shows only while the phone knows of no contact`() = runTest {
        val viewModel = ContactsCardViewModel(repository, FakeContactsPreferences(), Clock.systemUTC())
        read(viewModel.visible)
        assertTrue(viewModel.visible.value)

        repository.set(fakeContact(1, optedOut = true))
        assertFalse(viewModel.visible.value)

        repository.set()
        assertTrue(viewModel.visible.value)
    }

    @Test
    fun `not now hides the card for three days and no longer`() = runTest {
        val start = Instant.parse("2026-10-09T10:00:00Z")
        val preferences = FakeContactsPreferences()
        val clock = MovableClock(start)
        val viewModel = ContactsCardViewModel(repository, preferences, clock)
        read(viewModel.visible)

        viewModel.onNotNow()

        assertFalse(viewModel.visible.value)
        assertEquals(start.plus(Duration.ofDays(3)).toEpochMilli(), preferences.current)

        // The next time Home opens, two days and then three days later.
        clock.now = start.plus(Duration.ofDays(2))
        val early = ContactsCardViewModel(repository, preferences, clock)
        read(early.visible)
        assertFalse(early.visible.value)

        clock.now = start.plus(Duration.ofDays(3))
        val later = ContactsCardViewModel(repository, preferences, clock)
        read(later.visible)
        assertTrue(later.visible.value)
    }
}
