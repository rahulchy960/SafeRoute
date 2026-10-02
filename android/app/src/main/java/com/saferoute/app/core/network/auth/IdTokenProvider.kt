// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.auth

import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * The only link between the network layer and sign-in (ADR 0009).
 *
 * Contract: returns the signed-in user's Firebase ID token, or null when nobody is signed in.
 * `forceRefresh = true` asks for a new token even if the current one has not expired; it is
 * used once after the server answered 401. The network layer asks again for every request and
 * never keeps a token beyond that one request; caching is the implementation's business.
 *
 * `suspend` means the function may pause without blocking a thread, which a token refresh over
 * the network needs.
 */
interface IdTokenProvider {
    suspend fun idToken(forceRefresh: Boolean): String?
}

/** Used until sign-in exists (P009): nobody is signed in, so there is never a token. */
class SignedOutIdTokenProvider @Inject constructor() : IdTokenProvider {
    override suspend fun idToken(forceRefresh: Boolean): String? = null
}

/**
 * Calls the suspend function from OkHttp's blocking world.
 *
 * `runBlocking` blocks the current thread until the token is there. That is acceptable here and
 * only here: interceptors and the authenticator run on an OkHttp worker thread, never on the
 * main (UI) thread. Any failure becomes an [IOException], because OkHttp treats every other
 * exception thrown on its threads as a crash.
 */
internal fun IdTokenProvider.idTokenBlocking(forceRefresh: Boolean): String? =
    try {
        runBlocking { idToken(forceRefresh) }
    } catch (e: IOException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while getting an ID token", e)
    } catch (e: Exception) {
        throw IOException("Could not get an ID token", e)
    }

internal const val AUTHORIZATION_HEADER = "Authorization"

internal fun bearer(token: String): String = "Bearer $token"
