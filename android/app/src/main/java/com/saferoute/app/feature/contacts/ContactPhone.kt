// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import com.saferoute.app.core.auth.INDIA_CALLING_CODE
import com.saferoute.app.core.auth.normaliseIndianMobile

/** The longest name a contact can have; the server applies the same limit. */
const val CONTACT_NAME_MAX = 80

// \p{Z} covers every kind of space, including the non-breaking one found in copied text.
private val separators = Regex("""[\s\p{Z}\-().]""")

/** The server's rule: `+`, a country code that does not start with 0, 7 to 15 digits in all. */
private val e164 = Regex("""\+[1-9][0-9]{6,14}""")

/** Control characters and the line and paragraph separators: never part of a name. */
private val nameForbidden = Regex("""[\p{Cc}\p{Zl}\p{Zp}]""")

/**
 * Turns what a person typed, or what the phone's contact picker returned, into the E.164 form
 * the server accepts, or returns null when it is not a number the app can use.
 *
 * - Without a `+`, the number is read as Indian: ten digits starting with 6 to 9, optionally
 *   after `0`, `91` or `0091`. Landlines and short codes are not accepted: an SOS text needs a
 *   mobile number.
 * - With `+91`, the same ten-digit rule applies.
 * - With any other `+` country code, the digits are kept as they are and only the overall shape
 *   is checked. The app knows no other country's numbering plan (a follow-up evaluates a
 *   library for that).
 *
 * The result is never logged.
 */
fun normaliseContactPhone(input: String): String? {
    val compact = input.trim().replace(separators, "")
    return when {
        compact.startsWith(INDIA_CALLING_CODE) || !compact.startsWith("+") -> normaliseIndianMobile(compact)
        e164.matches(compact) -> compact
        else -> null
    }
}

/** The trimmed name, or null when it is empty, too long or contains control characters. */
fun normaliseContactName(input: String): String? {
    val name = input.trim()
    return name.takeIf { it.isNotEmpty() && it.length <= CONTACT_NAME_MAX && !nameForbidden.containsMatchIn(it) }
}
