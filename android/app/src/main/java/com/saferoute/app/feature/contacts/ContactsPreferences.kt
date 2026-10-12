// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** How long "Not now" on the Home card keeps the card away. */
const val CARD_SNOOZE_DAYS = 3L

/** The flags the contacts feature remembers on the phone besides the contacts themselves. */
interface ContactsPreferences {

    /**
     * Until when the "Add emergency contacts" card on Home stays hidden, in milliseconds since
     * 1970 (UTC); 0 when it was never put off.
     */
    val cardSnoozedUntil: Flow<Long>

    suspend fun snoozeCardUntil(epochMillis: Long)

    /**
     * The version of the `sos_alerts` notice the user has agreed to, as last heard from the
     * server or as just granted on this phone; null when none or withdrawn. A flag, not
     * personal data. It is what lets an SOS decide WITHOUT the network whether alerts may be
     * sent (ADR 0010, ADR 0027).
     */
    val alertsNoticeVersion: Flow<String?>

    suspend fun setAlertsNoticeVersion(version: String?)

    /**
     * When the contact list was last fetched from the server and written to this phone, in
     * milliseconds since 1970 (UTC); 0 when never, or when the copy was emptied since. A
     * point in time, nothing about a person. The "SOS setup" screen shows how old the copy is.
     */
    val contactsSyncedAt: Flow<Long>

    suspend fun setContactsSyncedAt(epochMillis: Long)

    /** The one-time offer of "SOS setup" after the first contact has been answered. */
    val setupOfferShown: Flow<Boolean>

    suspend fun setSetupOfferShown()

    /**
     * Until when the card "Not ready" on Home stays hidden after "Not now", in milliseconds
     * since 1970 (UTC); 0 when it was never put off.
     */
    val setupCardSnoozedUntil: Flow<Long>

    suspend fun snoozeSetupCardUntil(epochMillis: Long)
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

    override val alertsNoticeVersion: Flow<String?> = dataStore.data
        // An unreadable file means "not agreed": alerts stay off, which is the careful side.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[ALERTS_NOTICE_VERSION] }

    override suspend fun setAlertsNoticeVersion(version: String?) {
        dataStore.edit { if (version == null) it.remove(ALERTS_NOTICE_VERSION) else it[ALERTS_NOTICE_VERSION] = version }
    }

    override val contactsSyncedAt: Flow<Long> = dataStore.data
        // An unreadable file reads as "never": the screen then offers Refresh.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[CONTACTS_SYNCED_AT] ?: 0L }

    override suspend fun setContactsSyncedAt(epochMillis: Long) {
        dataStore.edit { it[CONTACTS_SYNCED_AT] = epochMillis }
    }

    // An unreadable file must not hide a warning: the offer and the card are simply shown.
    private val safe = dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val setupOfferShown: Flow<Boolean> = safe.map { it[SETUP_OFFER_SHOWN] ?: false }

    override suspend fun setSetupOfferShown() {
        dataStore.edit { it[SETUP_OFFER_SHOWN] = true }
    }

    override val setupCardSnoozedUntil: Flow<Long> = safe.map { it[SETUP_CARD_SNOOZED_UNTIL] ?: 0L }

    override suspend fun snoozeSetupCardUntil(epochMillis: Long) {
        dataStore.edit { it[SETUP_CARD_SNOOZED_UNTIL] = epochMillis }
    }

    private companion object {
        val SNOOZED_UNTIL = longPreferencesKey("contacts_card_snoozed_until")
        val ALERTS_NOTICE_VERSION = stringPreferencesKey("alerts_notice_version_agreed")
        val CONTACTS_SYNCED_AT = longPreferencesKey("contacts_synced_at")
        val SETUP_OFFER_SHOWN = booleanPreferencesKey("sos_setup_offer_shown")
        val SETUP_CARD_SNOOZED_UNTIL = longPreferencesKey("sos_setup_card_snoozed_until")
    }
}
