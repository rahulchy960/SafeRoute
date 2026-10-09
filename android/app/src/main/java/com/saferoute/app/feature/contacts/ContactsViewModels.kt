// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.data.MAX_SOS_CONTACTS
import com.saferoute.app.core.session.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The ViewModels of the emergency contacts screens (ADR 0024).
 *
 * A ViewModel outlives a rotation of the screen and holds what the screen shows as a
 * StateFlow. Nothing here is saved to disk: what a person typed, a contact and above all the
 * invite link live in memory and are gone when the process ends. The state classes that hold
 * such values hide them in toString(), so they cannot reach a log.
 */

/** How long a StateFlow built from the database keeps reading after the screen went away. */
private const val STOP_TIMEOUT_MILLIS = 5_000L

// The list --------------------------------------------------------------------------------

data class ContactsUiState(
    /** Null until the copy on the phone has been read; then the list, oldest first. */
    val contacts: List<EmergencyContact>? = null,
    val refreshing: Boolean = false,
    /** The last refresh found no connection: the list is read-only for now. */
    val offline: Boolean = false,
    /** Something to tell the user once; null says nothing. */
    val error: ContactsError? = null,
    /** "Stop SOS alerts and remove all contacts" is being sent. */
    val stopping: Boolean = false,
    /** Everything was removed: say so once. */
    val stopped: Boolean = false,
) {
    val canAdd: Boolean get() = (contacts?.size ?: 0) < MAX_SOS_CONTACTS

    override fun toString(): String = "ContactsUiState(hidden)"
}

/** The list of contacts. It shows the copy on the phone at once and fetches in the background. */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
    private val appLocale: AppLocale,
) : ViewModel() {

    private val local = MutableStateFlow(ContactsUiState())

    val state: StateFlow<ContactsUiState> = combine(repository.contacts, local) { contacts, extra ->
        extra.copy(contacts = contacts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ContactsUiState())

    init {
        refresh(silent = true)
    }

    /** The refresh button. */
    fun onRefresh() = refresh(silent = false)

    /**
     * @param silent the fetch nobody asked for (the screen opened): being offline shows the
     * read-only line, and no other failure is mentioned.
     */
    private fun refresh(silent: Boolean) {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.refresh()
            val error = (result as? ContactsResult.Failed)?.error
            local.update {
                it.copy(
                    refreshing = false,
                    offline = error == ContactsError.NoConnection,
                    error = error.takeUnless { silent || error == ContactsError.NoConnection },
                )
            }
        }
    }

    /** Confirmed in the dialog: withdraw `sos_alerts`; the server deletes every contact. */
    fun onStopConfirmed() {
        if (local.value.stopping) return
        local.update { it.copy(stopping = true, error = null) }
        viewModelScope.launch {
            val result = repository.withdrawConsent(appLocale.current())
            local.update {
                when (result) {
                    is ContactsResult.Ok -> it.copy(stopping = false, stopped = true, offline = false)
                    is ContactsResult.Failed -> it.copy(stopping = false, error = result.error)
                }
            }
        }
    }

    fun onMessageShown() = local.update { it.copy(error = null, stopped = false) }
}

// Adding ----------------------------------------------------------------------------------

sealed interface AddContactUiState {
    /** Asking the server whether the `sos_alerts` consent is on record. */
    data object Checking : AddContactUiState

    /** The check failed (offline, server trouble). Nothing can be added right now. */
    data class CheckFailed(val error: ContactsError) : AddContactUiState

    /** The notice to agree to before the first contact. Nothing is pre-selected. */
    data class Notice(val sending: Boolean = false, val error: ContactsError? = null) : AddContactUiState

    data class Form(
        val name: String = "",
        val phone: String = "",
        val nameInvalid: Boolean = false,
        val phoneInvalid: Boolean = false,
        val saving: Boolean = false,
        val error: ContactsError? = null,
        /** What went wrong with the phone's contact picker, if anything. */
        val pickProblem: PickProblem? = null,
    ) : AddContactUiState {
        override fun toString(): String = "Form(hidden)"
    }

    /** Saved: the screen moves on to the invite for this contact. */
    data class Saved(val contactId: String) : AddContactUiState
}

enum class PickProblem { Unreadable, NoContactsApp }

/**
 * Adds one contact. Before the first one it shows the `sos_alerts` notice: the server is asked
 * whether the consent is on record, so it is asked just in time and never assumed (ADR 0010).
 * "Not now" needs no function here: the screen simply closes, and nothing was sent.
 */
@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val repository: ContactsRepository,
    private val appLocale: AppLocale,
) : ViewModel() {

    private val _state = MutableStateFlow<AddContactUiState>(AddContactUiState.Checking)
    val state: StateFlow<AddContactUiState> = _state.asStateFlow()

    /** The number of a save that failed for lack of a connection; see [onSave]. */
    private var lostAnswerFor: String? = null

    init {
        check()
    }

    /** Also "Try again" after a failed check. */
    fun check() {
        _state.value = AddContactUiState.Checking
        viewModelScope.launch {
            _state.value = when (val result = repository.hasConsent()) {
                is ContactsResult.Ok ->
                    if (result.value) AddContactUiState.Form() else AddContactUiState.Notice()
                is ContactsResult.Failed -> AddContactUiState.CheckFailed(result.error)
            }
        }
    }

    /** "I agree" on the notice. */
    fun onAgree() {
        val notice = _state.value as? AddContactUiState.Notice ?: return
        if (notice.sending) return
        _state.value = notice.copy(sending = true, error = null)
        viewModelScope.launch {
            _state.value = when (val result = repository.grantConsent(appLocale.current())) {
                is ContactsResult.Ok -> AddContactUiState.Form()
                is ContactsResult.Failed -> AddContactUiState.Notice(error = result.error)
            }
        }
    }

    private inline fun form(change: (AddContactUiState.Form) -> AddContactUiState.Form) {
        _state.update { if (it is AddContactUiState.Form) change(it) else it }
    }

    fun onNameChange(value: String) = form { it.copy(name = value, nameInvalid = false, error = null) }

    fun onPhoneChange(value: String) = form { it.copy(phone = value, phoneInvalid = false, error = null) }

    /** The phone's contact picker returned a name and a number: put them in the form. */
    fun onPicked(picked: PickedContact) = form {
        it.copy(
            name = picked.name.take(CONTACT_NAME_MAX),
            phone = picked.number,
            nameInvalid = false,
            phoneInvalid = false,
            error = null,
            pickProblem = null,
        )
    }

    fun onPickProblem(problem: PickProblem) = form { it.copy(pickProblem = problem) }

    fun onSave() {
        val form = _state.value as? AddContactUiState.Form ?: return
        if (form.saving) return
        val name = normaliseContactName(form.name)
        val phone = normaliseContactPhone(form.phone)
        if (name == null || phone == null) {
            _state.value = form.copy(nameInvalid = name == null, phoneInvalid = phone == null)
            return
        }
        _state.value = form.copy(saving = true, error = null, pickProblem = null)
        viewModelScope.launch {
            _state.value = when (val result = repository.add(name, phone)) {
                is ContactsResult.Ok -> AddContactUiState.Saved(result.value.id)
                is ContactsResult.Failed -> onSaveFailed(form, phone, result.error)
            }
        }
    }

    private suspend fun onSaveFailed(
        form: AddContactUiState.Form,
        phone: String,
        error: ContactsError,
    ): AddContactUiState {
        // The earlier try lost its answer but reached the server: "already there" for the same
        // number is then the contact this very form created. Carry on as if it had answered.
        if (error == ContactsError.AlreadyExists && lostAnswerFor == phone) {
            repository.contacts.first().firstOrNull { it.phoneE164 == phone }?.let {
                return AddContactUiState.Saved(it.id)
            }
        }
        lostAnswerFor = phone.takeIf { error == ContactsError.NoConnection }
        // The consent was withdrawn elsewhere since the check: show the notice again.
        if (error == ContactsError.ConsentRequired) return AddContactUiState.Notice()
        return form.copy(saving = false, error = error)
    }
}

// Inviting --------------------------------------------------------------------------------

enum class InviteStep {
    /** What will happen, and the button that starts it. */
    Intro,

    /** Asking the server for the link. */
    Preparing,

    /** The SMS app was opened; back in the app: "Did you send the invite?" */
    AskSent,

    /** This phone has no app that can write an SMS. */
    NoSmsApp,

    /** Nothing more to do: the screen closes. */
    Done,
}

/** What the screen hands to the SMS app, once. The link is in it: never log or save this. */
class InviteSms internal constructor(val phoneE164: String, val link: InviteLink) {
    override fun toString(): String = "InviteSms(hidden)"
}

data class InviteUiState(
    /** Null while the contact is being read, or when it no longer exists. */
    val contactName: String? = null,
    val missing: Boolean = false,
    val step: InviteStep = InviteStep.Intro,
    val confirming: Boolean = false,
    val error: ContactsError? = null,
    /** Set for one moment: the screen opens the SMS app with it and reports back. */
    val smsToOpen: InviteSms? = null,
) {
    override fun toString(): String = "InviteUiState(hidden)"
}

/**
 * The invite for one contact. The app sends nothing: it asks the server for an opt-out link,
 * opens the phone's SMS app with a message that contains it, and afterwards asks the user
 * whether they sent it. The link exists in this object only between the server's answer and
 * the moment the SMS app is opened.
 */
@HiltViewModel
class InviteViewModel @Inject constructor(
    private val repository: ContactsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val contactId: String = savedStateHandle.get<String>(CONTACT_ID_ARGUMENT).orEmpty()

    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    private var phoneE164: String? = null

    init {
        viewModelScope.launch {
            val contact = repository.contacts.first().firstOrNull { it.id == contactId }
            phoneE164 = contact?.phoneE164
            _state.update { it.copy(contactName = contact?.name, missing = contact == null) }
        }
    }

    /** "Write the invite SMS". */
    fun onWrite() {
        val phone = phoneE164 ?: return
        if (_state.value.step == InviteStep.Preparing) return
        _state.update { it.copy(step = InviteStep.Preparing, error = null) }
        viewModelScope.launch {
            when (val result = repository.createInvite(contactId)) {
                is ContactsResult.Ok -> _state.update { it.copy(smsToOpen = InviteSms(phone, result.value)) }
                is ContactsResult.Failed -> _state.update { it.copy(step = InviteStep.Intro, error = result.error) }
            }
        }
    }

    /** The screen tried to open the SMS app. Either way the link is dropped here. */
    fun onSmsOpened(opened: Boolean) {
        _state.update {
            it.copy(smsToOpen = null, step = if (opened) InviteStep.AskSent else InviteStep.NoSmsApp)
        }
    }

    /** "Yes, I sent it". */
    fun onSent() {
        if (_state.value.confirming) return
        _state.update { it.copy(confirming = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.confirmInvite(contactId)) {
                is ContactsResult.Ok -> _state.update { it.copy(confirming = false, step = InviteStep.Done) }
                is ContactsResult.Failed -> _state.update { it.copy(confirming = false, error = result.error) }
            }
        }
    }

    /** "Later", "Done", or the back arrow: nothing is recorded. */
    fun onLater() = _state.update { it.copy(step = InviteStep.Done, smsToOpen = null) }
}

// One contact -----------------------------------------------------------------------------

data class ContactDetailUiState(
    val loading: Boolean = true,
    /** Null after loading: the contact is gone (removed here or on the server). */
    val contact: EmergencyContact? = null,
    val busy: Boolean = false,
    val error: ContactsError? = null,
    /** The contact was removed by this screen: go back. */
    val removed: Boolean = false,
) {
    override fun toString(): String = "ContactDetailUiState(hidden)"
}

/** One contact: rename it, invite again, remove it. */
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val repository: ContactsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val contactId: String = savedStateHandle.get<String>(CONTACT_ID_ARGUMENT).orEmpty()
    private val local = MutableStateFlow(ContactDetailUiState())

    val state: StateFlow<ContactDetailUiState> = combine(repository.contacts, local) { contacts, extra ->
        extra.copy(loading = false, contact = contacts.firstOrNull { it.id == contactId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ContactDetailUiState())

    /** @return false when [name] is not a valid name; nothing was sent then. */
    fun onRename(name: String): Boolean {
        val valid = normaliseContactName(name) ?: return false
        change { repository.rename(contactId, valid) }
        return true
    }

    /** Confirmed in the dialog. */
    fun onRemoveConfirmed() = change(removes = true) { repository.remove(contactId) }

    private fun change(removes: Boolean = false, call: suspend () -> ContactsResult<*>) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = call()
            local.update {
                when (result) {
                    is ContactsResult.Ok -> it.copy(busy = false, removed = removes)
                    is ContactsResult.Failed -> it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    fun onMessageShown() = local.update { it.copy(error = null) }
}

// The card on Home ------------------------------------------------------------------------

/**
 * Whether Home shows "Add emergency contacts": only while the phone knows of no contact, and
 * not within [CARD_SNOOZE_DAYS] days of "Not now". It reads the phone only.
 */
@HiltViewModel
class ContactsCardViewModel @Inject constructor(
    repository: ContactsRepository,
    private val preferences: ContactsPreferences,
    private val clock: Clock,
) : ViewModel() {

    val visible: StateFlow<Boolean> = combine(
        repository.contacts.map { it.isEmpty() },
        preferences.cardSnoozedUntil,
    ) { none, snoozedUntil -> none && clock.millis() >= snoozedUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    fun onNotNow() {
        viewModelScope.launch {
            preferences.snoozeCardUntil(clock.millis() + Duration.ofDays(CARD_SNOOZE_DAYS).toMillis())
        }
    }
}

/** The name of the navigation argument that carries a contact's id (a server-made UUID). */
const val CONTACT_ID_ARGUMENT = "contactId"
