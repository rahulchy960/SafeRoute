// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Phone sign-in as the rest of the app sees it (ADR 0012).
 *
 * Only `core/auth` knows that Firebase is behind it. Screens, ViewModels and the session code
 * use this interface, so tests replace it with a fake and never talk to Firebase.
 *
 * How phone sign-in works: the app asks Firebase to send an SMS with a 6-digit code to a phone
 * number; the user types the code (or Android reads the SMS for them); Firebase checks it and
 * the user is signed in. From then on Firebase can hand out short-lived ID tokens that prove to
 * the SafeRoute API who is calling.
 */
interface PhoneAuthGateway {

    /** The signed-in user, or null. Survives app restarts: Firebase stores the session. */
    val currentUser: AuthUser?

    /** Emits the current value at once, then again on every sign-in and sign-out. */
    val authState: Flow<AuthUser?>

    /**
     * Asks for an SMS code to be sent to [phoneE164] (for example `+91` and ten digits).
     *
     * The returned flow reports what happens next and stays open until the collector stops
     * collecting (cancel it when the screen goes away).
     *
     * @param activity needed by Firebase to prove that the request comes from the real app. It
     * normally does so silently (Play Integrity); as a fallback it opens a browser tab with a
     * reCAPTCHA check from this activity.
     */
    fun startVerification(phoneE164: String, activity: Activity): Flow<VerificationEvent>

    /** Like [startVerification], but tells Firebase it is a repeat for the same number. */
    fun resend(phoneE164: String, activity: Activity): Flow<VerificationEvent>

    /** Signs in with the code the user typed. [verificationId] comes from [VerificationEvent.CodeSent]. */
    suspend fun verifyCode(verificationId: String, code: String): SignInResult

    /**
     * The signed-in user's ID token, or null when nobody is signed in.
     *
     * An ID token is valid for about an hour. Firebase keeps a long-lived refresh token and
     * swaps it for a new ID token when needed; [forceRefresh] asks for a new one right away.
     */
    suspend fun idToken(forceRefresh: Boolean): String?

    suspend fun signOut()
}

/**
 * Says "somebody is signed in" and nothing more. It deliberately carries no user id and no
 * phone number, so neither can end up in a log line or a saved state. Account details come from
 * the SafeRoute API (`GET /v1/me`).
 */
data object AuthUser

sealed interface VerificationEvent {

    /** The SMS was requested. Ask the user for the code and pass this id to `verifyCode`. */
    data class CodeSent(val verificationId: String) : VerificationEvent

    /** The user is signed in without typing: Android read the SMS, or Firebase skipped it. */
    data object SignedIn : VerificationEvent

    data class Failed(val error: PhoneAuthError) : VerificationEvent
}

sealed interface SignInResult {
    data object Success : SignInResult

    data class Failure(val error: PhoneAuthError) : SignInResult
}

/**
 * Why sign-in failed, in terms a screen can explain. It never carries the SDK's message: that
 * text can contain the phone number or project details.
 */
enum class PhoneAuthError {
    /** The number is not one Firebase accepts. */
    INVALID_PHONE,

    /** The typed code is not the one that was sent. */
    WRONG_CODE,

    /** The code or the verification is too old; a new SMS is needed. */
    CODE_EXPIRED,

    /** Too many attempts from this phone or for this number; wait and try later. */
    TOO_MANY_REQUESTS,

    /** The project's SMS quota is used up, the region is not allowed, or the account is disabled. */
    QUOTA_OR_BLOCKED,

    /** No connection to Firebase. */
    NETWORK,

    /** Firebase could not confirm that the request comes from the real app. */
    APP_NOT_VERIFIED,

    UNKNOWN,
}
