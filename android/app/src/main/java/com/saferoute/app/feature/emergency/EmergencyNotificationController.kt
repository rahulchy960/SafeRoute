// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.saferoute.app.core.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The two things the shortcut remembers. Both are yes or no; neither says anything else. */
interface EmergencyShortcutPreferences {
    /** The user switched "Emergency shortcut in notifications" on. */
    val notificationEnabled: Flow<Boolean>

    /** The one-time offer after the first use of the SOS control has been shown. */
    val offerShown: Flow<Boolean>

    suspend fun setNotificationEnabled(enabled: Boolean)

    suspend fun setOfferShown()
}

/**
 * In the app's one DataStore file (see `SessionModule`): two flags, nothing about the user.
 * Signing out clears the file, and with it the switch: the notification is then removed, and a
 * person who signs in again turns it on again.
 */
class DataStoreEmergencyShortcutPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : EmergencyShortcutPreferences {

    // An unreadable file must not post a notification nobody asked for: fall back to "off".
    private val safe = dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val notificationEnabled: Flow<Boolean> = safe.map { it[ENABLED] ?: false }
    override val offerShown: Flow<Boolean> = safe.map { it[OFFER_SHOWN] ?: false }

    override suspend fun setNotificationEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    override suspend fun setOfferShown() {
        dataStore.edit { it[OFFER_SHOWN] = true }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("emergency_notification_enabled")
        val OFFER_SHOWN = booleanPreferencesKey("emergency_notification_offer_shown")
    }
}

/**
 * Decides whether the notification is on screen. One rule, used everywhere:
 * **shown if, and only if, the user's switch is on and Android allows it.**
 *
 * [sync] is called when the app opens, after a restart, after an update, and whenever the
 * switch changes. Because it only compares the two facts and posts or removes, calling it too
 * often does no harm: posting the same notification again replaces it silently.
 */
@Singleton
class EmergencyNotificationController @Inject constructor(
    private val preferences: EmergencyShortcutPreferences,
    private val gate: NotificationGate,
    private val notifier: EmergencyNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) {

    suspend fun sync() {
        val wanted = preferences.notificationEnabled.first()
        if (wanted && gate.block() == NotificationBlock.None) notifier.post() else notifier.cancel()
    }

    /** For callers that are not coroutines (an activity's `onStart`). */
    fun syncInBackground() {
        scope.launch { sync() }
    }

    /**
     * Follows the switch for as long as the process lives: turning it off removes the
     * notification at once, and so does signing out (which clears the stored switch).
     */
    fun start() {
        scope.launch {
            preferences.notificationEnabled.distinctUntilChanged().collect { sync() }
        }
    }
}

/**
 * Puts the notification back after the phone restarted and after the app was updated: both
 * remove every notification. Android starts the app's process for a moment to deliver these
 * two broadcasts; this receiver only calls [EmergencyNotificationController.sync] and ends.
 *
 * It starts no activity and no service. If the switch is off or notifications are not
 * allowed, it does nothing. An app the user force-stopped receives nothing until it is opened
 * again; then the app's own start puts the notification back.
 */
class EmergencyShortcutReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun controller(): EmergencyNotificationController

        @ApplicationScope
        fun scope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!handles(intent.action)) return
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        // goAsync: the work reads a file, which must not happen on the main thread; the
        // system keeps the process alive until finish() is called.
        val pending = goAsync()
        dependencies.scope().launch {
            try {
                dependencies.controller().sync()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Only these two; anything else sent to the receiver is ignored. */
        fun handles(action: String?): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
    }
}
