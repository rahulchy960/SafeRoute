// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * @property crashReports the person's choice "Send crash reports". Off until they switch it on.
 * @property asked whether the choice has been put to them once.
 */
data class DiagnosticsSettings(
    val crashReports: Boolean = false,
    val asked: Boolean = false,
)

/**
 * The choice about crash reports. Two flags on this phone only: it is not part of the
 * account and is never sent to the SafeRoute server.
 */
interface DiagnosticsPreferences {
    val settings: Flow<DiagnosticsSettings>

    suspend fun read(): DiagnosticsSettings = settings.first()

    /** Records the choice; making it also counts as having been asked. */
    suspend fun setCrashReports(enabled: Boolean)

    suspend fun setAsked()
}

class DataStoreDiagnosticsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DiagnosticsPreferences {

    // A file that cannot be read counts as "off": never as a yes nobody gave.
    override val settings: Flow<DiagnosticsSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { DiagnosticsSettings(it[CRASH_REPORTS] ?: false, it[ASKED] ?: false) }

    override suspend fun setCrashReports(enabled: Boolean) {
        dataStore.edit {
            it[CRASH_REPORTS] = enabled
            it[ASKED] = true
        }
    }

    override suspend fun setAsked() {
        dataStore.edit { it[ASKED] = true }
    }

    private companion object {
        val CRASH_REPORTS = booleanPreferencesKey("crash_reports_enabled")
        val ASKED = booleanPreferencesKey("crash_reports_asked")
    }
}
