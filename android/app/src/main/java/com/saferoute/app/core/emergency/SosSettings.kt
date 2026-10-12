// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import kotlinx.coroutines.flow.Flow

/** In which language the alert is written. */
enum class AlertLanguageChoice {
    /** The language SafeRoute is shown in. The default. */
    APP,
    EN,
    BN,
}

/**
 * What the user chose for their alerts (P014c2b). All three are optional, and none decides
 * whether an SOS can start.
 *
 * @property name the name the contacts read in the alert ("... needs help."); null when
 *   none was entered, and the alert then says so in other words. It is what the user typed
 *   for this purpose: the app has no other name for them and asks for no proof of one.
 * @property countdownSound a short tone each second of the countdown. Off by default: a
 *   phone that makes a noise can put its owner at risk.
 */
data class SosSettings(
    val name: String? = null,
    val language: AlertLanguageChoice = AlertLanguageChoice.APP,
    val countdownSound: Boolean = false,
) {
    // The generated toString() would print the name.
    override fun toString(): String = "SosSettings(hidden)"
}

/**
 * Where [SosSettings] are kept: on this phone only, in the app's private database. Nothing
 * here touches the network. They are erased together with the emergency contacts: when the
 * user stops SOS alerts, signs out, or the account is blocked.
 */
interface SosSettingsStore {

    /** The settings now, and again whenever they change. */
    val settings: Flow<SosSettings>

    suspend fun current(): SosSettings

    /** Stores [name] as it may appear in a message ([messageName]); null or blank removes it. */
    suspend fun setName(name: String?)

    suspend fun setLanguage(language: AlertLanguageChoice)

    suspend fun setCountdownSound(on: Boolean)

    /** Removes everything: the name, and the two choices go back to their defaults. */
    suspend fun clear()
}

/** A short tone, once per second of the countdown, when the user switched it on. */
fun interface CountdownTone {
    fun play()
}
