// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What screens and ViewModels use to follow and drive the session. [SessionRepository] is the
 * implementation; tests use a fake, so no UI test needs Firebase or a server.
 */
interface Session {

    /** A `StateFlow` always has a current value; screens collect it and redraw when it changes. */
    val state: StateFlow<SessionState>

    /** The flags stored on the phone (for example whether the welcome screen was passed). */
    val flags: Flow<SessionFlags>

    /**
     * Works out the state from scratch. Call it when the app starts, after sign-in, and when the
     * user taps "Try again".
     */
    suspend fun refresh()

    /**
     * Sign-in with Firebase just succeeded: check the account, create it if needed, and move
     * on. Unlike [refresh] it does nothing when the session has already left "signed out",
     * so a second report of the same sign-in does not start a second check.
     *
     * The work does not belong to the caller: it finishes even if the calling screen is
     * replaced while it runs.
     */
    suspend fun onSignedIn()

    /** Welcome screen passed. */
    suspend fun markWelcomeSeen()

    /** "I am 18 or older". Stored on the phone only; the server learns it at account creation. */
    suspend fun confirmAdult()

    /** "I am under 18": a flag on this phone and nothing else. No network call, no sign-in. */
    suspend fun declareUnder18()

    /**
     * "I agree" on the consent notice shown in [noticeLocale] (`en` or `bn`).
     *
     * For a new user this only stores the acceptance; it is sent when the account is created.
     * For a signed-in user it is sent right away (`PUT /v1/me/consents/account_core`).
     *
     * Declining needs no function: nothing is stored and nothing is sent.
     */
    suspend fun acceptNotice(noticeLocale: String)

    /** Signs out of Firebase and forgets the onboarding flags: the app starts over. */
    suspend fun signOut()

    /** Reads the signed-in user's account from the API for the Settings screen. */
    suspend fun account(): AccountResult
}

/**
 * The account as Settings shows it. The phone number is already masked: the full number never
 * leaves [SessionRepository], so no screen, saved state or log can contain it.
 */
data class AccountSummary(
    /** For example `+91 ••••• ••123`, or null when the account has no number. */
    val maskedPhone: String?,
    /** `user`, `moderator` or `admin`; a newer server may send something else. */
    val role: String,
    /** The account's language on the server: `en` or `bn`. */
    val locale: String,
    /** Purposes the user has consented to and not withdrawn, for example `account_core`. */
    val grantedPurposes: List<String>,
)

sealed interface AccountResult {
    data class Loaded(val account: AccountSummary) : AccountResult

    /** The account could not be read. [retryable]: trying again later may work. */
    data class Unavailable(val retryable: Boolean) : AccountResult
}

/** How many digits stay readable at the end of a masked phone number. */
private const val VISIBLE_DIGITS = 3

/**
 * `+919000000123` becomes `+91 ••••• ••123`: the country code and the last three digits stay,
 * everything else is a dot. Anything that is not an Indian E.164 number is masked completely,
 * so an unexpected format can never show a full number.
 */
fun maskPhone(phoneE164: String): String {
    val national = phoneE164.removePrefix("+91")
    if (!phoneE164.startsWith("+91") || national.length != 10 || !national.all(Char::isDigit)) {
        return "•••••"
    }
    val hidden = "•".repeat(national.length - VISIBLE_DIGITS)
    return "+91 ${hidden.take(5)} ${hidden.drop(5)}${national.takeLast(VISIBLE_DIGITS)}"
}
