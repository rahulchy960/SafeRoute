// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.data.ActiveSosContacts
import javax.inject.Inject

/**
 * Why a message was not sent by a build that limits who it texts: the number is not on that
 * build's list of test numbers. Stored as the action's error category.
 */
const val SKIP_DEBUG_NOT_ALLOWLISTED = "debug_not_allowlisted"

/**
 * A limit on who this build may text **by itself** (ADR 0027, note of P014b6).
 *
 * A release build has none. A debug build may text only the test numbers named when it was
 * built: a developer's or tester's account can hold the numbers of real people, and an SOS
 * that finishes its countdown would text every one of them.
 *
 * The limit is about automatic sending only. The phone's SMS app, where the user reads the
 * message and presses Send, is not limited.
 */
interface SmsRecipientLimit {
    /** True when this build limits its recipients at all. */
    val limited: Boolean

    /** Whether the app may text [phoneE164] by itself. Always true when not [limited]. */
    fun allows(phoneE164: String): Boolean
}

/** No limit: every contact may be texted. What a release build uses. */
object UnlimitedSmsRecipients : SmsRecipientLimit {
    override val limited: Boolean = false

    override fun allows(phoneE164: String): Boolean = true
}

/**
 * What a build that limits its recipients would do with an alert right now, for a notice on
 * the screens that start an SOS. Counts only: never a name or a number.
 */
data class SmsLimitNotice(
    /** False when the app cannot send by itself anyway (no SMS permission in this build or not granted). */
    val automaticPossible: Boolean,
    /** How many of the user's emergency contacts would be texted by the app itself. */
    val automaticRecipients: Int,
)

/** Works out the [SmsLimitNotice]. Reads the contacts from the phone only. */
class SmsLimitNotices @Inject constructor(
    private val limit: SmsRecipientLimit,
    private val contacts: ActiveSosContacts,
    private val modeSource: SmsModeSource,
) {
    /** Null in a build without a limit: there is nothing to say. */
    suspend fun current(): SmsLimitNotice? {
        if (!limit.limited) return null
        val possible = modeSource.mode() == SmsMode.AUTOMATIC
        val recipients = if (possible) contacts.current().count { limit.allows(it.phoneE164) } else 0
        return SmsLimitNotice(automaticPossible = possible, automaticRecipients = recipients)
    }
}
