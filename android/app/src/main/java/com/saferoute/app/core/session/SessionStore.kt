// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The few onboarding and session facts the app remembers on the phone.
 *
 * It holds flags and a notice version only. The phone number, ID tokens and SMS codes are
 * NEVER stored here or anywhere else by the app (Firebase keeps its own session).
 */
data class SessionFlags(
    /** The welcome screen was passed, so onboarding resumes after it. */
    val welcomeSeen: Boolean = false,
    /** The user said "I am 18 or older". */
    val ageConfirmed: Boolean = false,
    /** The user said "I am under 18". The app stays blocked; nothing else is recorded. */
    val under18: Boolean = false,
    /** Version of the consent notice the user accepted on this phone, or null. */
    val acceptedNoticeVersion: String? = null,
    /** Language the accepted notice was shown in: `en` or `bn`. */
    val acceptedNoticeLocale: String? = null,
    /**
     * The app reached Home at least once with this sign-in. With it the app opens without a
     * connection (device-first, Plan v7 §7); without it a first run must reach the server.
     */
    val readyOnce: Boolean = false,
)

interface SessionStore {

    /** Emits the current flags, then again after every change. */
    val flags: Flow<SessionFlags>

    suspend fun read(): SessionFlags = flags.first()

    suspend fun update(transform: (SessionFlags) -> SessionFlags)

    /** Back to a fresh install's state. */
    suspend fun clear()
}

/**
 * [SessionStore] on Jetpack DataStore (Preferences): a small key-value file that is read and
 * written asynchronously and safely from any thread. It replaces SharedPreferences. A database
 * (Room) is for many structured records; these are six values.
 *
 * Because it is on disk, the flags survive "process death": Android may kill the app while it
 * is in the background (for example while the user reads the SMS), and on return the app
 * continues at the right onboarding step.
 */
class DataStoreSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SessionStore {

    override val flags: Flow<SessionFlags> = dataStore.data
        // An unreadable file must not crash the app at start-up: treat it as a fresh install.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toFlags() }

    override suspend fun update(transform: (SessionFlags) -> SessionFlags) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toFlags())
            prefs[WELCOME_SEEN] = next.welcomeSeen
            prefs[AGE_CONFIRMED] = next.ageConfirmed
            prefs[UNDER_18] = next.under18
            prefs[READY_ONCE] = next.readyOnce
            prefs.setOrRemove(NOTICE_VERSION_KEY, next.acceptedNoticeVersion)
            prefs.setOrRemove(NOTICE_LOCALE_KEY, next.acceptedNoticeLocale)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toFlags() = SessionFlags(
        welcomeSeen = this[WELCOME_SEEN] ?: false,
        ageConfirmed = this[AGE_CONFIRMED] ?: false,
        under18 = this[UNDER_18] ?: false,
        acceptedNoticeVersion = this[NOTICE_VERSION_KEY],
        acceptedNoticeLocale = this[NOTICE_LOCALE_KEY],
        readyOnce = this[READY_ONCE] ?: false,
    )

    companion object {
        /** File name under the app's private files directory. */
        const val FILE_NAME = "session"

        private val WELCOME_SEEN = booleanPreferencesKey("welcome_seen")
        private val AGE_CONFIRMED = booleanPreferencesKey("age_confirmed")
        private val UNDER_18 = booleanPreferencesKey("under_18")
        private val NOTICE_VERSION_KEY = stringPreferencesKey("accepted_notice_version")
        private val NOTICE_LOCALE_KEY = stringPreferencesKey("accepted_notice_locale")
        private val READY_ONCE = booleanPreferencesKey("ready_once")
    }
}

private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}
