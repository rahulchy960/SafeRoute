// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import android.content.Context
import android.content.Intent
import com.saferoute.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val EXTRA_OPEN = "com.saferoute.app.extra.OPEN"
private const val OPEN_CONTACTS = "contacts"

/**
 * Opens the app at Emergency contacts. Used by the emergency screen's "Set up SOS alerts",
 * which lives in another activity and so cannot navigate by itself.
 *
 * The intent carries one fixed word and nothing about the user. SINGLE_TOP with CLEAR_TOP:
 * an app that is already open gets the request (`onNewIntent`) instead of a second copy.
 */
fun openContactsIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_OPEN, OPEN_CONTACTS)

/**
 * A screen another part of the app asked for, kept in memory until the navigation host has
 * gone there. It holds a yes or no. The host exists only for a signed-in session, so before
 * sign-in a request simply waits, and a new start of the app without one clears it.
 */
object OpenRequests {

    private val _contacts = MutableStateFlow(false)
    val contacts: StateFlow<Boolean> = _contacts.asStateFlow()

    /** Reads what [intent] asks for; null or an intent without a request asks for nothing. */
    fun accept(intent: Intent?) {
        _contacts.value = intent?.getStringExtra(EXTRA_OPEN) == OPEN_CONTACTS
    }

    /** The host has navigated. */
    fun done() {
        _contacts.value = false
    }
}
