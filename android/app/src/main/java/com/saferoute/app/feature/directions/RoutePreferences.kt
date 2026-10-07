// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import androidx.datastore.core.DataStore
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

/** The two things directions remember between app starts. Neither is a place or a route. */
data class RouteSettings(
    val mode: TravelMode = TravelMode.Walking,
    /** The "How routes work" note was read and continued past. */
    val introSeen: Boolean = false,
)

interface RoutePreferences {
    val settings: Flow<RouteSettings>

    suspend fun read(): RouteSettings = settings.first()

    suspend fun setMode(mode: TravelMode)

    suspend fun setIntroSeen()
}

/**
 * [RoutePreferences] in the app's one DataStore file (see `SessionModule`). It holds a choice
 * ("walking" or "driving") and a flag: never an origin, a destination or a route. Signing out
 * clears the file, and these two with it.
 */
class DataStoreRoutePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RoutePreferences {

    override val settings: Flow<RouteSettings> = dataStore.data
        // An unreadable file must not break directions: fall back to the defaults.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { RouteSettings(TravelMode.fromKey(it[MODE]), it[INTRO_SEEN] ?: false) }

    override suspend fun setMode(mode: TravelMode) {
        dataStore.edit { it[MODE] = mode.key }
    }

    override suspend fun setIntroSeen() {
        dataStore.edit { it[INTRO_SEEN] = true }
    }

    private companion object {
        val MODE = stringPreferencesKey("route_mode")
        val INTRO_SEEN = booleanPreferencesKey("route_intro_seen")
    }
}
