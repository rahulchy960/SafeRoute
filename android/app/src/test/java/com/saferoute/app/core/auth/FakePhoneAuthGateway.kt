// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import android.app.Activity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Obviously fake values. Tests assert that none of them ever reaches a log line.
 *
 * Phone numbers in tests are made-up patterns and are never used to send anything: the fake
 * below has no network, no Firebase and no SMS behind it.
 */
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

    @Volatile
    private var user: AuthUser? = if (signedIn) AuthUser else null

    // A SharedFlow, not a StateFlow: Firebase's listener can report the same state twice, and
    // a StateFlow would swallow the repeat.
    private val states = MutableSharedFlow<AuthUser?>(replay = 1, extraBufferCapacity = 16)
        .apply { tryEmit(user) }

    private fun setUser(value: AuthUser?) {
        user = value
        states.tryEmit(value)
    }

    /**
     * When true, `verifyCode` reports success but the user only appears when the test calls
     * [completeSignIn]: Firebase's auth state arriving a moment after the code was accepted.
     */
    var signInCompletesLater = false

    fun completeSignIn() = setUser(AuthUser)

    /** Firebase's listener fires again without anything having changed. */
    fun reportAuthStateAgain() {
        states.tryEmit(user)
    }

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

    override val currentUser: AuthUser? get() = user

    override val authState: Flow<AuthUser?> = states

    fun signInDirectly() = setUser(AuthUser)

    override fun startVerification(phoneE164: String, activity: Activity): Flow<VerificationEvent> =
        verification("startVerification")

    override fun resend(phoneE164: String, activity: Activity): Flow<VerificationEvent> =
        verification("resend")

    private fun verification(call: String): Flow<VerificationEvent> {
        calls += call
        if (VerificationEvent.SignedIn in nextVerification) setUser(AuthUser)
        return flowOf(*nextVerification.toTypedArray())
    }

    override suspend fun verifyCode(verificationId: String, code: String): SignInResult {
        calls += "verifyCode"
        verifyError?.let { return SignInResult.Failure(it) }
        if (verificationId != FAKE_VERIFICATION_ID) {
            return SignInResult.Failure(PhoneAuthError.CODE_EXPIRED)
        }
        if (code != FAKE_SMS_CODE) return SignInResult.Failure(PhoneAuthError.WRONG_CODE)
        if (!signInCompletesLater) setUser(AuthUser)
        return SignInResult.Success
    }

    override suspend fun idToken(forceRefresh: Boolean): String? {
        tokenRequests += forceRefresh
        idTokenFailure?.let { throw it }
        if (user == null) return null
        return if (forceRefresh) FAKE_REFRESHED_ID_TOKEN else FAKE_ID_TOKEN
    }

    override suspend fun signOut() {
        calls += "signOut"
        setUser(null)
    }
}
