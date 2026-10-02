// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** India's single emergency number. */
internal const val EmergencyNumber = "112"

/**
 * The intent that opens the phone app with [EmergencyNumber] already typed in.
 *
 * `ACTION_DIAL` only *shows* the dialer: the person still presses the call button. It needs no
 * permission. `ACTION_CALL` would place the call immediately, needs the CALL_PHONE permission,
 * and is not allowed to call emergency numbers at all. Never switch to it.
 */
internal fun emergencyDialIntent(): Intent =
    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", EmergencyNumber, null))

/**
 * Opens the phone dialer with 112.
 *
 * @return `false` when the device has no app that can dial (some tablets, for example). The
 *   caller must then show the number so the person can use another phone.
 */
internal fun openEmergencyDialer(context: Context): Boolean =
    try {
        context.startActivity(emergencyDialIntent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
