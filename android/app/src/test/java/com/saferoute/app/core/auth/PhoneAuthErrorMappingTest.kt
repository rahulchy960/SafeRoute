// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every Firebase failure type becomes one [PhoneAuthError]. The exceptions are built by hand;
 * nothing here talks to Firebase. Runs under Robolectric because the Firebase exception classes
 * use Android framework helpers.
 */
@RunWith(AndroidJUnit4::class)
class PhoneAuthErrorMappingTest {

    private val secret = "secret-sdk-message"

    @Test
    fun `a wrong or missing code`() {
        val wrong = FirebaseAuthInvalidCredentialsException("ERROR_INVALID_VERIFICATION_CODE", secret)
        assertEquals(PhoneAuthError.WRONG_CODE, wrong.toPhoneAuthError())
        assertEquals(PhoneAuthError.WRONG_CODE, phoneAuthErrorForCode("ERROR_MISSING_VERIFICATION_CODE"))
    }

    @Test
    fun `an expired code or verification`() {
        val expired = FirebaseAuthInvalidCredentialsException("ERROR_SESSION_EXPIRED", secret)
        assertEquals(PhoneAuthError.CODE_EXPIRED, expired.toPhoneAuthError())
        assertEquals(PhoneAuthError.CODE_EXPIRED, phoneAuthErrorForCode("ERROR_INVALID_VERIFICATION_ID"))
        assertEquals(PhoneAuthError.CODE_EXPIRED, phoneAuthErrorForCode("ERROR_MISSING_VERIFICATION_ID"))
    }

    @Test
    fun `an invalid phone number`() {
        val invalid = FirebaseAuthInvalidCredentialsException("ERROR_INVALID_PHONE_NUMBER", secret)
        assertEquals(PhoneAuthError.INVALID_PHONE, invalid.toPhoneAuthError())
        assertEquals(PhoneAuthError.INVALID_PHONE, phoneAuthErrorForCode("ERROR_MISSING_PHONE_NUMBER"))
    }

    @Test
    fun `too many requests`() {
        assertEquals(
            PhoneAuthError.TOO_MANY_REQUESTS,
            FirebaseTooManyRequestsException(secret).toPhoneAuthError(),
        )
    }

    @Test
    fun `quota used up, region not allowed, or account disabled`() {
        for (code in listOf("ERROR_QUOTA_EXCEEDED", "ERROR_OPERATION_NOT_ALLOWED", "ERROR_USER_DISABLED")) {
            assertEquals(code, PhoneAuthError.QUOTA_OR_BLOCKED, FirebaseAuthException(code, secret).toPhoneAuthError())
        }
    }

    @Test
    fun `no connection`() {
        assertEquals(PhoneAuthError.NETWORK, FirebaseNetworkException(secret).toPhoneAuthError())
        assertEquals(PhoneAuthError.NETWORK, UnknownHostException(secret).toPhoneAuthError())
    }

    @Test
    fun `the app could not be verified`() {
        assertEquals(
            PhoneAuthError.APP_NOT_VERIFIED,
            FirebaseAuthMissingActivityForRecaptchaException().toPhoneAuthError(),
        )
        val codes = listOf(
            "ERROR_APP_NOT_AUTHORIZED",
            "ERROR_CAPTCHA_CHECK_FAILED",
            "ERROR_INVALID_RECAPTCHA_TOKEN",
            "ERROR_MISSING_RECAPTCHA_TOKEN",
            "ERROR_WEB_CONTEXT_CANCELED",
        )
        for (code in codes) {
            assertEquals(code, PhoneAuthError.APP_NOT_VERIFIED, FirebaseAuthException(code, secret).toPhoneAuthError())
        }
    }

    @Test
    fun `anything else is unknown, never a crash`() {
        assertEquals(PhoneAuthError.UNKNOWN, FirebaseAuthException("ERROR_FROM_A_NEWER_SDK", secret).toPhoneAuthError())
        assertEquals(PhoneAuthError.UNKNOWN, phoneAuthErrorForCode(""))
        assertEquals(PhoneAuthError.UNKNOWN, IllegalStateException(secret).toPhoneAuthError())
    }

    @Test
    fun `the result never carries the SDK's message`() {
        val errors = listOf(
            FirebaseAuthInvalidCredentialsException("ERROR_INVALID_VERIFICATION_CODE", secret),
            FirebaseTooManyRequestsException(secret),
            FirebaseNetworkException(secret),
            IllegalStateException(secret),
        ).map { it.toPhoneAuthError() }
        for (error in errors) {
            val asEvent = VerificationEvent.Failed(error).toString() + SignInResult.Failure(error)
            assertEquals(false, asEvent.contains(secret))
        }
    }
}
