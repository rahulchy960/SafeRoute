// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import android.content.Context
import androidx.room.Room
import com.saferoute.app.core.data.di.DatabaseModule
import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.feature.contacts.di.ContactsModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Obviously fake people: "Test Contact N" and numbers from +91 90000 100NN. Nobody real. */
fun fakeContact(
    n: Int,
    invited: Boolean = false,
    optedOut: Boolean = false,
) = EmergencyContact(
    id = "00000000-0000-4000-8000-0000000000${n.toString().padStart(2, '0')}",
    name = "Test Contact $n",
    phoneE164 = "+9190000100${n.toString().padStart(2, '0')}",
    createdAt = Instant.parse("2026-10-09T09:00:00Z").plusSeconds(n.toLong()),
    invitedAt = if (invited) Instant.parse("2026-10-09T10:00:00Z") else null,
    optedOutAt = if (optedOut) Instant.parse("2026-10-09T11:00:00Z") else null,
)

const val FAKE_INVITE_URL = "https://example.invalid/c#FAKE-TOKEN-0000000000AA"

/**
 * Emergency contacts that live in a list: no server, no database. It applies the simple rules a
 * screen relies on (the limit, duplicates, opted-out contacts) and records every call.
 *
 * Set [failWith] to make every call that needs the server fail, for example with
 * [ContactsError.NoConnection]; reading [contacts] keeps working, as in the real one.
 */
class FakeContactsRepository : ContactsRepository {

    private val list = MutableStateFlow<List<EmergencyContact>>(emptyList())

    /** One entry per call, for example `add` or `remove`. Never a name or a number. */
    val calls = CopyOnWriteArrayList<String>()

    var consentGranted = false
    var failWith: ContactsError? = null

    val current: List<EmergencyContact> get() = list.value

    fun set(vararg contacts: EmergencyContact) {
        list.value = contacts.toList()
    }

    override val contacts: Flow<List<EmergencyContact>> = list

    private fun <T> answer(call: String, block: () -> ContactsResult<T>): ContactsResult<T> {
        calls += call
        return failWith?.let { ContactsResult.Failed(it) } ?: block()
    }

    override suspend fun refresh() = answer("refresh") { ContactsResult.Ok(Unit) }

    override suspend fun hasConsent() = answer("hasConsent") { ContactsResult.Ok(consentGranted) }

    override suspend fun grantConsent(noticeLocale: String) = answer("grantConsent($noticeLocale)") {
        consentGranted = true
        ContactsResult.Ok(Unit)
    }

    override suspend fun withdrawConsent(noticeLocale: String) = answer("withdrawConsent($noticeLocale)") {
        consentGranted = false
        list.value = emptyList()
        ContactsResult.Ok(Unit)
    }

    override suspend fun add(name: String, phoneE164: String): ContactsResult<EmergencyContact> = answer("add") {
        when {
            !consentGranted -> ContactsResult.Failed(ContactsError.ConsentRequired)
            list.value.any { it.phoneE164 == phoneE164 } -> ContactsResult.Failed(ContactsError.AlreadyExists)
            list.value.size >= 5 -> ContactsResult.Failed(ContactsError.LimitReached)
            else -> {
                val contact = fakeContact(list.value.size + 1).copy(name = name, phoneE164 = phoneE164)
                list.value += contact
                ContactsResult.Ok(contact)
            }
        }
    }

    override suspend fun rename(id: String, name: String): ContactsResult<EmergencyContact> = answer("rename") {
        val found = list.value.firstOrNull { it.id == id }
            ?: return@answer ContactsResult.Failed(ContactsError.NotFound)
        val renamed = found.copy(name = name)
        list.value = list.value.map { if (it.id == id) renamed else it }
        ContactsResult.Ok(renamed)
    }

    override suspend fun remove(id: String): ContactsResult<Unit> = answer("remove") {
        list.value = list.value.filterNot { it.id == id }
        ContactsResult.Ok(Unit)
    }

    override suspend fun createInvite(id: String): ContactsResult<InviteLink> = answer("createInvite") {
        val found = list.value.firstOrNull { it.id == id }
        when {
            found == null -> ContactsResult.Failed(ContactsError.NotFound)
            found.optedOutAt != null -> ContactsResult.Failed(ContactsError.OptedOut)
            else -> ContactsResult.Ok(InviteLink(FAKE_INVITE_URL))
        }
    }

    override suspend fun confirmInvite(id: String): ContactsResult<Unit> = answer("confirmInvite") {
        list.value = list.value.map {
            if (it.id == id) it.copy(invitedAt = Instant.parse("2026-10-09T10:00:00Z")) else it
        }
        ContactsResult.Ok(Unit)
    }

    override suspend fun clearLocal() {
        calls += "clearLocal"
        list.value = emptyList()
    }
}

/**
 * Replaces [ContactsModule] in every Hilt test, so a test that starts `MainActivity` never
 * calls the server this machine's build points at.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ContactsModule::class])
object FakeContactsModule {

    @Provides
    @Singleton
    fun provideFakeContactsRepository(): FakeContactsRepository = FakeContactsRepository()

    @Provides
    fun provideContactsRepository(fake: FakeContactsRepository): ContactsRepository = fake
}

/** A database in memory, gone when the test ends. */
fun inMemoryDatabase(context: Context): SafeRouteDatabase =
    Room.inMemoryDatabaseBuilder(context, SafeRouteDatabase::class.java).allowMainThreadQueries().build()

/** Replaces [DatabaseModule] in every Hilt test: no database file is created by a test. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafeRouteDatabase = inMemoryDatabase(context)
}
