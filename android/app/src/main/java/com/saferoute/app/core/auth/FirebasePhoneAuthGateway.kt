// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import dagger.Lazy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** How long Android waits to read the SMS by itself. Also the earliest a resend is allowed. */
internal const val SMS_TIMEOUT_SECONDS = 60L

/**
 * [PhoneAuthGateway] on top of Firebase Authentication. This is the only class that calls the
 * Firebase Auth SDK, and it is kept thin on purpose: it has no decisions of its own to test, so
 * the unit tests use a fake gateway and this class is checked on a real phone.
 *
 * It never logs, and it never passes on an SDK message (see [toPhoneAuthError]).
 *
 * `Lazy<FirebaseAuth>`: Firebase is first touched when sign-in is first needed, not while Hilt
 * builds the object graph.
 */
@Singleton
class FirebasePhoneAuthGateway @Inject constructor(
    private val firebaseAuth: Lazy<FirebaseAuth>,
) : PhoneAuthGateway {

    private val auth: FirebaseAuth get() = firebaseAuth.get()

    /**
     * Firebase hands this out with the first SMS and wants it back for a resend. It only lives
     * in memory: after the app process dies the next request is simply a new verification.
     */
    @Volatile
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    override val currentUser: AuthUser?
        get() = auth.currentUser?.let { AuthUser }

    /**
     * `callbackFlow` turns a listener-style API into a Flow: the listener is added when
     * somebody starts collecting and removed (`awaitClose`) when they stop.
     */
    override val authState: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.let { AuthUser }) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override fun startVerification(phoneE164: String, activity: Activity): Flow<VerificationEvent> =
        verification(phoneE164, activity, resend = false)

    override fun resend(phoneE164: String, activity: Activity): Flow<VerificationEvent> =
        verification(phoneE164, activity, resend = true)

    private fun verification(
        phoneE164: String,
        activity: Activity,
        resend: Boolean,
    ): Flow<VerificationEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            // Android read the SMS (or Firebase verified the number instantly): finish sign-in
            // without the user typing anything.
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                launch { send(signIn(credential).toEvent()) }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                trySend(VerificationEvent.Failed(e.toPhoneAuthError()))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken,
            ) {
                resendToken = token
                trySend(VerificationEvent.CodeSent(verificationId))
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneE164)
            .setTimeout(SMS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .apply { if (resend) resendToken?.let(::setForceResendingToken) }
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)

        awaitClose { }
    }

    override suspend fun verifyCode(verificationId: String, code: String): SignInResult =
        try {
            signIn(PhoneAuthProvider.getCredential(verificationId, code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // getCredential rejects an empty id or code before any network call.
            SignInResult.Failure(e.toPhoneAuthError())
        }

    private suspend fun signIn(credential: PhoneAuthCredential): SignInResult =
        try {
            // `await()` suspends until the Play services Task finishes, without blocking a thread.
            auth.signInWithCredential(credential).await()
            SignInResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failure(e.toPhoneAuthError())
        }

    /**
     * A failure to refresh is thrown on purpose: the network layer turns it into "no
     * connection", which is not a sign-out. The one exception is an account that no longer
     * exists or is disabled: then there is no token, the API answers 401 and the app signs out.
     */
    override suspend fun idToken(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: FirebaseAuthInvalidUserException) {
            null
        }
    }

    override suspend fun signOut() {
        resendToken = null
        auth.signOut()
    }
}

private fun SignInResult.toEvent(): VerificationEvent = when (this) {
    SignInResult.Success -> VerificationEvent.SignedIn
    is SignInResult.Failure -> VerificationEvent.Failed(error)
}
