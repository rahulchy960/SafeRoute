// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import android.app.Activity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Obviously fake values. Tests assert that none of them ever reaches a log line. */
const val FAKE_ID_TOKEN = "fake-id-token-aaaa.bbbb.cccc"
const val FAKE_REFRESHED_ID_TOKEN = "fake-refreshed-id-token-dddd.eeee.ffff"
const val FAKE_PHONE_E164 = "+910000000001"
const val FAKE_SMS_CODE = "000000"
const val FAKE_VERIFICATION_ID = "fake-verification-id"

/**
 * Stands in for Firebase in tests: no network, no Play services, no SMS.
 *
 * A test scripts what the next verification does ([nextVerification]) and which code is right
 * ([FAKE_SMS_CODE] unless [verifyError] is set), then checks [calls] and [currentUser].
 */
class FakePhoneAuthGateway(signedIn: Boolean = false) : PhoneAuthGateway {

    private val user = MutableStateFlow(if (signedIn) AuthUser else null)

    /** What `startVerification` and `resend` report. Default: the SMS was sent. */
    var nextVerification: List<VerificationEvent> =
        listOf(VerificationEvent.CodeSent(FAKE_VERIFICATION_ID))

    /** When set, `verifyCode` fails with it whatever the code is. */
    var verifyError: PhoneAuthError? = null

    /** When set, `idToken` throws it (a refresh that fails, for example without a connection). */
    var idTokenFailure: Exception? = null

    /** One entry per call, without any argument values. */
    val calls = CopyOnWriteArrayList<String>()

    /** The `forceRefresh` value of every `idToken` call. */
    val tokenRequests = CopyOnWriteArrayList<Boolean>()

    override val currentUser: AuthUser? get() = user.value

    override val authState: Flow<AuthUser?> = user

    fun signInDirectly() {
        user.value = AuthUser
    }

    override fun startVerification(phoneE164: String, activity: Activity): Flow<VerificationEvent> =
        verification("startVerification")

    override fun resend(phoneE164: String, activity: Activity): Flow<VerificationEvent> =
        verification("resend")

    private fun verification(call: String): Flow<VerificationEvent> {
        calls += call
        if (VerificationEvent.SignedIn in nextVerification) user.value = AuthUser
        return flowOf(*nextVerification.toTypedArray())
    }

    override suspend fun verifyCode(verificationId: String, code: String): SignInResult {
        calls += "verifyCode"
        verifyError?.let { return SignInResult.Failure(it) }
        if (verificationId != FAKE_VERIFICATION_ID) {
            return SignInResult.Failure(PhoneAuthError.CODE_EXPIRED)
        }
        if (code != FAKE_SMS_CODE) return SignInResult.Failure(PhoneAuthError.WRONG_CODE)
        user.value = AuthUser
        return SignInResult.Success
    }

    override suspend fun idToken(forceRefresh: Boolean): String? {
        tokenRequests += forceRefresh
        idTokenFailure?.let { throw it }
        if (user.value == null) return null
        return if (forceRefresh) FAKE_REFRESHED_ID_TOKEN else FAKE_ID_TOKEN
    }

    override suspend fun signOut() {
        calls += "signOut"
        user.value = null
    }
}
