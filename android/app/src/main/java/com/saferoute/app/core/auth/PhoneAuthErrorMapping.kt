// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import java.io.IOException

/**
 * Turns whatever Firebase threw into a [PhoneAuthError]. Only the exception's type and its
 * machine-readable error code are used; the message is dropped.
 *
 * The codes are `ERROR_...` strings found in firebase-auth 24.2.0. Too many requests and network
 * failures have their own exception classes instead of a code.
 */
internal fun Throwable.toPhoneAuthError(): PhoneAuthError = when (this) {
    is FirebaseNetworkException, is IOException -> PhoneAuthError.NETWORK
    is FirebaseTooManyRequestsException -> PhoneAuthError.TOO_MANY_REQUESTS
    is FirebaseAuthMissingActivityForRecaptchaException -> PhoneAuthError.APP_NOT_VERIFIED
    is FirebaseAuthException -> phoneAuthErrorForCode(errorCode)
    else -> PhoneAuthError.UNKNOWN
}

internal fun phoneAuthErrorForCode(code: String): PhoneAuthError = when (code) {
    "ERROR_INVALID_VERIFICATION_CODE", "ERROR_MISSING_VERIFICATION_CODE" -> PhoneAuthError.WRONG_CODE

    "ERROR_SESSION_EXPIRED", "ERROR_INVALID_VERIFICATION_ID", "ERROR_MISSING_VERIFICATION_ID" ->
        PhoneAuthError.CODE_EXPIRED

    "ERROR_INVALID_PHONE_NUMBER", "ERROR_MISSING_PHONE_NUMBER" -> PhoneAuthError.INVALID_PHONE

    "ERROR_QUOTA_EXCEEDED", "ERROR_OPERATION_NOT_ALLOWED", "ERROR_USER_DISABLED" ->
        PhoneAuthError.QUOTA_OR_BLOCKED

    "ERROR_APP_NOT_AUTHORIZED",
    "ERROR_CAPTCHA_CHECK_FAILED",
    "ERROR_INVALID_RECAPTCHA_TOKEN",
    "ERROR_MISSING_RECAPTCHA_TOKEN",
    "ERROR_WEB_CONTEXT_CANCELED",
    -> PhoneAuthError.APP_NOT_VERIFIED

    else -> PhoneAuthError.UNKNOWN
}
