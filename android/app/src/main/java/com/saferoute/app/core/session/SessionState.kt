// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

/**
 * Version of the consent notice the app shows. It is sent with the user's consent and compared
 * with what the server has on record: when the notice text changes, change this value and
 * every user is asked again (ADR 0010).
 *
 * The notice is a DRAFT until a lawyer has reviewed it. The text arrives with the onboarding
 * screens and is mirrored in `docs/legal/`.
 */
const val NOTICE_VERSION = "2026-10-draft1"

/** The consent purpose recorded at sign-up: verify the phone number and keep the account. */
const val PURPOSE_ACCOUNT_CORE = "account_core"

/**
 * Where the user stands between "just installed" and "can use the app". One value at a time; the
 * navigation layer decides which screen each one means.
 *
 * A `sealed interface` has a fixed list of subtypes, so a `when` over it must handle them all.
 */
sealed interface SessionState {

    /** Not known yet (the app just started, or a check is running). Show a progress indicator. */
    data object Loading : SessionState

    /** Has not yet said "I am 18 or older". Nothing is stored or sent before that. */
    data object NeedsAge : SessionState

    /**
     * Has to read and accept the current consent notice. Either a new user, or a signed-in user
     * whose recorded consent is missing or was given for an older notice.
     */
    data object NeedsConsent : SessionState

    /** Age and consent are done; the phone number has to be verified. */
    data object SignedOut : SessionState

    /** Signed in with Firebase; the SafeRoute account is being created. */
    data object NeedsBootstrap : SessionState

    /** Everything is in place: show Home. */
    data object Ready : SessionState

    /** The app cannot be used. Final until the app's data is cleared. */
    data class Blocked(val reason: BlockReason) : SessionState

    /**
     * The check could not be completed. [retryable] is true when trying again later may work
     * (no connection, server unavailable).
     */
    data class Error(val retryable: Boolean) : SessionState
}

enum class BlockReason {
    /** The user said they are under 18. Only a flag on this phone records it. */
    UNDER_18,

    /** The server says this account was deleted. */
    ACCOUNT_DELETED,
}
