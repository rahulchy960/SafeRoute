// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** How long "Not now" on the Home card keeps the card away. */
const val CARD_SNOOZE_DAYS = 3L

/** The one thing the contacts feature remembers on the phone besides the contacts themselves. */
interface ContactsPreferences {

    /**
     * Until when the "Add emergency contacts" card on Home stays hidden, in milliseconds since
     * 1970 (UTC); 0 when it was never put off.
     */
    val cardSnoozedUntil: Flow<Long>

    suspend fun snoozeCardUntil(epochMillis: Long)
}

/**
 * [ContactsPreferences] in the app's one DataStore file (see `SessionModule`). It holds a point
 * in time and nothing about any person. Signing out clears the file, and this with it.
 */
class DataStoreContactsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ContactsPreferences {

    override val cardSnoozedUntil: Flow<Long> = dataStore.data
        // An unreadable file must not break Home: the card is simply shown.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[SNOOZED_UNTIL] ?: 0L }

    override suspend fun snoozeCardUntil(epochMillis: Long) {
        dataStore.edit { it[SNOOZED_UNTIL] = epochMillis }
    }

    private companion object {
        val SNOOZED_UNTIL = longPreferencesKey("contacts_card_snoozed_until")
    }
}
