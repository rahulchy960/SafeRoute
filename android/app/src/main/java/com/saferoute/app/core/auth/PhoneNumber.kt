// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

/** India's country calling code. SafeRoute currently supports Indian mobile numbers only. */
const val INDIA_CALLING_CODE = "+91"

// \u00a0 (matched by the regex engine) is the non-breaking space found in copied text.
private val separators = Regex("""[\s\u00a0\-().]""")
private val indianMobile = Regex("""[6-9]\d{9}""")

/**
 * Turns what a person typed into an Indian mobile number in E.164 form (`+91` followed by ten
 * digits), or returns null when it is not one.
 *
 * Accepted: ten digits starting with 6, 7, 8 or 9, optionally with spaces, dashes, dots or
 * brackets, and optionally prefixed with `+91`, `91`, `0091` or a trunk `0`.
 *
 * E.164 is the international format without spaces that Firebase and the backend expect.
 * The result is never stored on the phone and never logged.
 */
fun normaliseIndianMobile(input: String): String? {
    val compact = input.trim().replace(separators, "")
    val national = when {
        compact.startsWith("+91") -> compact.removePrefix("+91")
        compact.startsWith("0091") -> compact.removePrefix("0091")
        compact.length == 12 && compact.startsWith("91") -> compact.removePrefix("91")
        compact.length == 11 && compact.startsWith("0") -> compact.removePrefix("0")
        else -> compact
    }
    return if (indianMobile.matches(national)) INDIA_CALLING_CODE + national else null
}
