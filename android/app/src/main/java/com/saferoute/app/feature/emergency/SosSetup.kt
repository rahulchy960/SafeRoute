// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.emergency.AlertLanguageChoice
import com.saferoute.app.core.emergency.ReadinessLocation
import com.saferoute.app.core.emergency.SosReadiness
import com.saferoute.app.core.emergency.SosSettings
import com.saferoute.app.core.emergency.SosSettingsStore
import com.saferoute.app.core.session.AppLocale
import com.saferoute.app.core.session.LOCALE_BENGALI
import com.saferoute.app.feature.contacts.ContactsRepository
import com.saferoute.app.feature.contacts.ContactsResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the "SOS setup" screen shows. Counts and yes-or-no answers only: no contact's name
 * or number and no position is in it.
 *
 * @property bengali the language SafeRoute is shown in (what "Same as SafeRoute" means).
 * @property settings the name, the language and the countdown sound the user chose. The name
 *   is the user's own, shown to them on their own screen; the type hides it when printed.
 * @property refreshFailed the last "Refresh" could not reach the server; the copy is unchanged.
 */
data class SosSetupUiState(
    val readiness: SosReadiness,
    val activeContacts: Int,
    val location: ReadinessLocation,
    val bengali: Boolean,
    val settings: SosSettings = SosSettings(),
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
)

/**
 * Reads, at this moment, everything that decides whether an SOS from this phone would reach
 * anyone, and hands it to [sosReadiness].
 *
 * There is no background work and nothing is remembered: [onResume] reads it all again every
 * time the screen comes to the front, because the user may have changed a permission in
 * the phone's settings meanwhile. It asks for no permission, starts no location updates and
 * sends nothing anywhere, except the list of contacts being fetched when the user taps
 * "Refresh". Nothing is logged.
 */
@HiltViewModel
class SosSetupViewModel @Inject constructor(
    private val reader: SosReadinessReader,
    private val repository: ContactsRepository,
    private val locale: AppLocale,
    private val settings: SosSettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow<SosSetupUiState?>(null)

    /** Null until the first reading is done (a moment). */
    val state: StateFlow<SosSetupUiState?> = _state.asStateFlow()

    /** The screen came to the front: read everything again. */
    fun onResume() {
        viewModelScope.launch { read() }
    }

    /** "Refresh": fetches the contact list once. A failure changes nothing on the phone. */
    fun onRefresh() {
        val shown = _state.value ?: return
        if (shown.refreshing) return
        _state.value = shown.copy(refreshing = true, refreshFailed = false)
        viewModelScope.launch {
            val failed = repository.refresh() is ContactsResult.Failed
            read(refreshFailed = failed)
        }
    }

    /** Saves the name for alerts; null or blank removes it. Kept on this phone only. */
    fun onNameSave(name: String?) = change { settings.setName(name) }

    fun onLanguageChange(language: AlertLanguageChoice) = change { settings.setLanguage(language) }

    fun onCountdownSoundChange(on: Boolean) = change { settings.setCountdownSound(on) }

    private fun change(write: suspend () -> Unit) {
        viewModelScope.launch {
            write()
            read()
        }
    }

    private suspend fun read(refreshFailed: Boolean = false) {
        val reading = reader.read()
        _state.value = SosSetupUiState(
            readiness = reading.readiness,
            activeContacts = reading.facts.activeContacts,
            location = reading.facts.location,
            bengali = locale.current() == LOCALE_BENGALI,
            settings = settings.current(),
            refreshFailed = refreshFailed,
        )
    }
}

/*
 * The three places in the phone's own settings that the screen can open. Opening a settings
 * page needs NO permission and changes nothing by itself: the user decides there. The app
 * does not ask to be exempt from battery optimisation (that would need a permission Google
 * Play restricts); it only shows the way.
 */

/** Settings → Apps → SafeRoute: permissions and battery are both reached from this page. */
internal fun appDetailsSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** The notification settings of SafeRoute. */
internal fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** The phone's own "Location" switch. */
internal fun locationSettingsIntent(): Intent =
    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens [intent]; false when the phone has no screen for it (some do not). Never throws. */
internal fun Context.openSettings(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
