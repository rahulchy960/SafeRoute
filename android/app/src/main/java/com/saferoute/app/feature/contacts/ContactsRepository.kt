// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import com.saferoute.app.core.data.local.ContactDao
import com.saferoute.app.core.data.local.ContactEntity
import com.saferoute.app.core.network.ApiConfig
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.ProblemCodes
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.errors.apiCallNoContent
import com.saferoute.app.core.network.generated.api.ContactsApi
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.model.Contact
import com.saferoute.app.core.network.generated.model.CreateContactRequest
import com.saferoute.app.core.network.generated.model.RenameContactRequest
import com.saferoute.app.core.network.generated.model.SetConsentRequest
import com.saferoute.app.core.session.LOCALE_BENGALI
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The consent purpose that covers emergency contacts and, later, SOS alerts (ADR 0010). */
const val PURPOSE_SOS_ALERTS = "sos_alerts"

/**
 * Version of the `sos_alerts` notice the app shows before the first contact is added. Change it
 * when the notice text changes. The notice is a DRAFT until a lawyer has reviewed it.
 */
const val SOS_ALERTS_NOTICE_VERSION = "2026-10-alerts-draft2"

/** Where a contact stands. Decided from the two times, opt-out first. */
enum class ContactStatus {
    /** The user has not said that the invite SMS was sent. */
    NotInvited,

    /** The user said the invite SMS was sent. Not a delivery receipt. */
    Invited,

    /** The contact opted out: never alerted, and cannot be invited again. */
    OptedOut,
}

/** One emergency contact, in the app's own terms. */
data class EmergencyContact(
    val id: String,
    val name: String,
    val phoneE164: String,
    val createdAt: Instant,
    val invitedAt: Instant?,
    val optedOutAt: Instant?,
) {
    val status: ContactStatus
        get() = when {
            optedOutAt != null -> ContactStatus.OptedOut
            invitedAt != null -> ContactStatus.Invited
            else -> ContactStatus.NotInvited
        }

    // A name and a phone number of a person who is not a user: keep them out of logs.
    override fun toString(): String = "EmergencyContact(hidden)"
}

/**
 * The opt-out link for one invite SMS: `<API address>/c#<token>`. The token sits in the URL
 * FRAGMENT, which a browser never sends to a server. It exists in memory only: never saved,
 * never logged, and gone when the invite screen closes.
 */
class InviteLink internal constructor(val url: String) {
    override fun toString(): String = "InviteLink(hidden)"
}

/** Why something about the contacts did not work. */
sealed interface ContactsError {
    /** The server could not be reached. Reading still works; changing the list does not. */
    data object NoConnection : ContactsError

    /** The `sos_alerts` consent is not granted: show its notice first. */
    data object ConsentRequired : ContactsError

    /** The user already has the most contacts allowed. */
    data object LimitReached : ContactsError

    /** This number is already one of the user's contacts. */
    data object AlreadyExists : ContactsError

    /** This person opted out: they cannot be added or invited again. */
    data object OptedOut : ContactsError

    /** The number is the user's own. */
    data object OwnNumber : ContactsError

    /** The server refused the name or the number. */
    data object Invalid : ContactsError

    /** Too many requests. [retryAfterSeconds] is null when waiting does not help. */
    data class RateLimited(val retryAfterSeconds: Int?) : ContactsError

    /** The contact no longer exists on the server. */
    data object NotFound : ContactsError

    /** The server is down. Not the user's fault. */
    data object Unavailable : ContactsError

    /** Anything else. */
    data object Unexpected : ContactsError
}

sealed interface ContactsResult<out T> {
    data class Ok<T>(val value: T) : ContactsResult<T> {
        override fun toString(): String = "Ok(hidden)"
    }

    data class Failed(val error: ContactsError) : ContactsResult<Nothing>
}

/**
 * The user's emergency contacts (ADR 0024).
 *
 * **The server is the source of truth; the phone keeps a copy.** [contacts] reads the copy, so
 * it works with no network. Every change goes to the server first and needs a connection; the
 * copy is replaced by the server's list after each successful fetch, and a FAILED fetch never
 * touches it.
 *
 * The app sends no message to a contact. [createInvite] only prepares the link for an SMS the
 * user sends from their own SMS app.
 *
 * Tests use `FakeContactsRepository`.
 */
interface ContactsRepository {

    /** The copy on the phone, oldest first. Emits again whenever it changes. */
    val contacts: Flow<List<EmergencyContact>>

    /** Fetches the server's list and replaces the copy with it. */
    suspend fun refresh(): ContactsResult<Unit>

    /** Whether the user's latest `sos_alerts` decision is "granted". */
    suspend fun hasConsent(): ContactsResult<Boolean>

    /** Records "I agree" to the `sos_alerts` notice shown in [noticeLocale] (`en` or `bn`). */
    suspend fun grantConsent(noticeLocale: String): ContactsResult<Unit>

    /**
     * Withdraws `sos_alerts`. The server deletes every contact in the same step; the copy on
     * the phone is emptied when the server confirmed. It cannot be undone.
     */
    suspend fun withdrawConsent(noticeLocale: String): ContactsResult<Unit>

    /**
     * Adds a contact. [name] and [phoneE164] are already normalised (`ContactPhone.kt`).
     * On [ContactsError.AlreadyExists] the copy has been refreshed, so a caller that was
     * retrying after a lost answer finds the contact in [contacts].
     */
    suspend fun add(name: String, phoneE164: String): ContactsResult<EmergencyContact>

    suspend fun rename(id: String, name: String): ContactsResult<EmergencyContact>

    suspend fun remove(id: String): ContactsResult<Unit>

    /** Asks the server for a new opt-out token and builds the link for the invite SMS. */
    suspend fun createInvite(id: String): ContactsResult<InviteLink>

    /** Records that the user said the invite SMS was sent. */
    suspend fun confirmInvite(id: String): ContactsResult<Unit>

    /** Empties the copy on the phone (sign-out, blocked account). Touches no server. */
    suspend fun clearLocal()
}

private const val STATUS_GRANTED = "granted"
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAVAILABLE = 503

@Singleton
class ApiContactsRepository @Inject constructor(
    private val api: ContactsApi,
    private val meApi: MeApi,
    private val dao: ContactDao,
    private val config: ApiConfig,
    private val preferences: ContactsPreferences,
) : ContactsRepository {

    /** One writer of the copy at a time. */
    private val writes = Mutex()

    /**
     * Goes up each time the copy is emptied on purpose. A fetch that started before then must
     * not write its (now somebody else's, or nobody's) list afterwards.
     */
    private var cleared = 0

    override val contacts: Flow<List<EmergencyContact>> =
        dao.observeAll().map { rows -> rows.map(ContactEntity::toContact) }

    /** The value of [cleared] now; pass it to [writeIfCurrent] when the server has answered. */
    private suspend fun epoch(): Int = writes.withLock { cleared }

    /** Runs [write] unless the copy was emptied on purpose since [epoch] was read. */
    private suspend fun writeIfCurrent(epoch: Int, write: suspend () -> Unit) {
        writes.withLock { if (cleared == epoch) write() }
    }

    override suspend fun refresh(): ContactsResult<Unit> {
        val epoch = epoch()
        return when (val result = apiCall { api.listContacts() }) {
            is ApiResult.Success -> {
                writeIfCurrent(epoch) { dao.replaceAll(result.value.items.map(Contact::toEntity)) }
                ContactsResult.Ok(Unit)
            }
            // The copy stays exactly as it was.
            is ApiResult.Failure -> ContactsResult.Failed(result.failure.toContactsError())
        }
    }

    /**
     * True only when the user's consent is granted **for the notice the app shows today**. A
     * consent given for an older notice counts as "not yet": the user is shown the current
     * notice again. The answer is also written to the phone, so that an SOS can decide
     * without the network whether alerts may be sent.
     */
    override suspend fun hasConsent(): ContactsResult<Boolean> =
        when (val result = apiCall { meApi.getMyConsents() }) {
            is ApiResult.Success -> {
                val current = result.value.items.any {
                    it.purpose == PURPOSE_SOS_ALERTS &&
                        it.status == STATUS_GRANTED &&
                        it.noticeVersion == SOS_ALERTS_NOTICE_VERSION
                }
                preferences.setAlertsNoticeVersion(SOS_ALERTS_NOTICE_VERSION.takeIf { current })
                ContactsResult.Ok(current)
            }
            // The phone keeps what it knew: a failed check neither gives nor takes consent.
            is ApiResult.Failure -> ContactsResult.Failed(result.failure.toContactsError())
        }

    override suspend fun grantConsent(noticeLocale: String): ContactsResult<Unit> {
        val result = setConsent(SetConsentRequest.Status.granted, noticeLocale)
        // Only after the server recorded it.
        if (result is ContactsResult.Ok) preferences.setAlertsNoticeVersion(SOS_ALERTS_NOTICE_VERSION)
        return result
    }

    override suspend fun withdrawConsent(noticeLocale: String): ContactsResult<Unit> {
        val result = setConsent(SetConsentRequest.Status.withdrawn, noticeLocale)
        // Only after the server said yes: until then the contacts still exist there.
        if (result is ContactsResult.Ok) clearLocal()
        return result
    }

    private suspend fun setConsent(status: SetConsentRequest.Status, noticeLocale: String): ContactsResult<Unit> {
        val request = SetConsentRequest(
            status = status,
            noticeVersion = SOS_ALERTS_NOTICE_VERSION,
            noticeLocale = when (noticeLocale) {
                LOCALE_BENGALI -> SetConsentRequest.NoticeLocale.bn
                else -> SetConsentRequest.NoticeLocale.en
            },
        )
        return when (val result = apiCall { meApi.setMyConsent(PURPOSE_SOS_ALERTS, request) }) {
            is ApiResult.Success -> ContactsResult.Ok(Unit)
            is ApiResult.Failure -> ContactsResult.Failed(result.failure.toContactsError())
        }
    }

    override suspend fun add(name: String, phoneE164: String): ContactsResult<EmergencyContact> {
        val epoch = epoch()
        val request = CreateContactRequest(name = name, phone = phoneE164)
        return when (val result = apiCall { api.createContact(request) }) {
            is ApiResult.Success -> stored(result.value, epoch)
            is ApiResult.Failure -> {
                val error = result.failure.toContactsError()
                // The number is on the server already: perhaps from an earlier try whose answer
                // was lost. Bring the copy up to date so the caller can find it.
                if (error == ContactsError.AlreadyExists) refresh()
                ContactsResult.Failed(error)
            }
        }
    }

    override suspend fun rename(id: String, name: String): ContactsResult<EmergencyContact> {
        val uuid = id.toUuid() ?: return ContactsResult.Failed(ContactsError.NotFound)
        val epoch = epoch()
        return when (val result = apiCall { api.renameContact(uuid, RenameContactRequest(name = name)) }) {
            is ApiResult.Success -> stored(result.value, epoch)
            is ApiResult.Failure -> failed(result.failure, id)
        }
    }

    override suspend fun remove(id: String): ContactsResult<Unit> {
        val uuid = id.toUuid() ?: return ContactsResult.Failed(ContactsError.NotFound)
        return when (val result = apiCallNoContent { api.deleteContact(uuid) }) {
            is ApiResult.Success -> {
                writes.withLock { dao.delete(id) }
                ContactsResult.Ok(Unit)
            }
            is ApiResult.Failure -> failed(result.failure, id)
        }
    }

    override suspend fun createInvite(id: String): ContactsResult<InviteLink> {
        val uuid = id.toUuid() ?: return ContactsResult.Failed(ContactsError.NotFound)
        // Without a real API address the link would lead nowhere: do not ask for a token.
        if (!config.isConfigured) return ContactsResult.Failed(ContactsError.Unavailable)
        return when (val result = apiCall { api.createContactInvite(uuid) }) {
            is ApiResult.Success -> ContactsResult.Ok(inviteLink(config, result.value.optOutToken))
            is ApiResult.Failure -> {
                val error = result.failure.toContactsError()
                // The contact opted out since the last fetch: show it.
                if (error == ContactsError.OptedOut) refresh()
                failed(result.failure, id)
            }
        }
    }

    override suspend fun confirmInvite(id: String): ContactsResult<Unit> {
        val uuid = id.toUuid() ?: return ContactsResult.Failed(ContactsError.NotFound)
        return when (val result = apiCallNoContent { api.confirmContactInvite(uuid) }) {
            is ApiResult.Success -> {
                // The answer has no body; the new "invited" time comes with the list. If this
                // fetch fails, the next one brings it.
                refresh()
                ContactsResult.Ok(Unit)
            }
            is ApiResult.Failure -> failed(result.failure, id)
        }
    }

    override suspend fun clearLocal() {
        writes.withLock {
            cleared++
            dao.clear()
        }
        // No contacts on this phone, no alerts from it.
        preferences.setAlertsNoticeVersion(null)
    }

    /** The server's answer IS the truth for this row: store it at once, no second request. */
    private suspend fun stored(contact: Contact, epoch: Int): ContactsResult<EmergencyContact> {
        val entity = contact.toEntity()
        writeIfCurrent(epoch) { dao.upsert(entity) }
        return ContactsResult.Ok(entity.toContact())
    }

    /** A contact the server no longer has is dropped from the copy too. */
    private suspend fun failed(failure: ApiFailure, id: String): ContactsResult.Failed {
        val error = failure.toContactsError()
        if (error == ContactsError.NotFound) writes.withLock { dao.delete(id) }
        return ContactsResult.Failed(error)
    }
}

/**
 * `<API address>/c#<token>`. The API address always ends with `/` (`ApiConfig`). The token is a
 * fragment: it is not part of the request a browser makes for the page.
 */
internal fun inviteLink(config: ApiConfig, token: String): InviteLink = InviteLink("${config.baseUrl}c#$token")

private fun String.toUuid(): UUID? = try {
    UUID.fromString(this)
} catch (e: IllegalArgumentException) {
    null
}

internal fun Contact.toEntity() = ContactEntity(
    id = id.toString(),
    name = name,
    phoneE164 = phoneE164,
    createdAt = createdAt.toInstant().toEpochMilli(),
    invitedAt = invitedAt?.toInstant()?.toEpochMilli(),
    optedOutAt = optedOutAt?.toInstant()?.toEpochMilli(),
)

internal fun ContactEntity.toContact() = EmergencyContact(
    id = id,
    name = name,
    phoneE164 = phoneE164,
    createdAt = Instant.ofEpochMilli(createdAt),
    invitedAt = invitedAt?.let(Instant::ofEpochMilli),
    optedOutAt = optedOutAt?.let(Instant::ofEpochMilli),
)

/**
 * 401 is not handled here: it falls into [ContactsError.Unexpected], and the session (which
 * checks the account on its own) decides what the user sees next.
 */
internal fun ApiFailure.toContactsError(): ContactsError = when (this) {
    ApiFailure.NoConnection -> ContactsError.NoConnection
    is ApiFailure.Problem -> when {
        code == ProblemCodes.CONSENT_REQUIRED -> ContactsError.ConsentRequired
        code == ProblemCodes.CONTACT_LIMIT_REACHED -> ContactsError.LimitReached
        code == ProblemCodes.CONTACT_EXISTS -> ContactsError.AlreadyExists
        code == ProblemCodes.CONTACT_OPTED_OUT -> ContactsError.OptedOut
        code == ProblemCodes.INVALID_CONTACT -> ContactsError.OwnNumber
        code == ProblemCodes.VALIDATION_ERROR -> ContactsError.Invalid
        status == HTTP_TOO_MANY_REQUESTS || code == ProblemCodes.RATE_LIMITED ->
            ContactsError.RateLimited(retryAfterSeconds)
        status == HTTP_NOT_FOUND -> ContactsError.NotFound
        status == HTTP_UNAVAILABLE -> ContactsError.Unavailable
        else -> ContactsError.Unexpected
    }
    is ApiFailure.Unexpected -> when (status) {
        HTTP_TOO_MANY_REQUESTS -> ContactsError.RateLimited(null)
        HTTP_UNAVAILABLE -> ContactsError.Unavailable
        else -> ContactsError.Unexpected
    }
    is ApiFailure.Unauthorized -> ContactsError.Unexpected
}
