// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import com.saferoute.app.BuildConfig
import com.saferoute.app.core.emergency.SmsRecipientLimit
import com.saferoute.app.feature.contacts.normaliseContactPhone
import javax.inject.Inject

/*
 * DEBUG builds only (ADR 0027, note of P014b6). The release build has a file of the same
 * name in src/release that sets no limit.
 *
 * Why: a debug build carries the SMS permission so that alerts can be tried on a phone. A
 * developer's or tester's account may hold the numbers of real people, and every SOS whose
 * countdown finishes would text them. So a debug build texts only numbers that were named,
 * on purpose, when it was built.
 */

/**
 * The test numbers a debug build may text by itself.
 *
 * [raw] is the Gradle property `saferoute.debugSmsAllowlist`: phone numbers separated by
 * commas. Each entry is read with the same rule as a contact's number ([normaliseContactPhone]),
 * so `+91 90000 10001`, `9000010001` and `+919000010001` are one number. An entry that is
 * not a usable number is ignored; a number named twice counts once.
 *
 * An empty list allows nobody: the app then sends nothing by itself and uses the phone's SMS
 * app. PERSONAL DATA: the numbers are never printed, logged or shown; only their count is.
 */
class DebugSmsAllowlist(raw: String) : SmsRecipientLimit {

    private val numbers: Set<String> = raw.split(',').mapNotNull { normaliseContactPhone(it) }.toSet()

    /** How many usable numbers the list holds. */
    val size: Int get() = numbers.size

    override val limited: Boolean = true

    // Compared in the normalised form, as a contact's number is stored.
    override fun allows(phoneE164: String): Boolean = (normaliseContactPhone(phoneE164) ?: phoneE164) in numbers

    override fun toString(): String = "DebugSmsAllowlist(hidden)"
}

/** The limit of this build: the list from `BuildConfig.DEBUG_SMS_ALLOWLIST`. */
class BuildSmsRecipientLimit @Inject constructor() :
    SmsRecipientLimit by DebugSmsAllowlist(BuildConfig.DEBUG_SMS_ALLOWLIST)
