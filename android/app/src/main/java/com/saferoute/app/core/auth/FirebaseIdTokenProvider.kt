// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import com.saferoute.app.core.network.auth.IdTokenProvider
import javax.inject.Inject

/**
 * Gives the network layer the signed-in user's ID token (the [IdTokenProvider] seam from P008,
 * ADR 0009).
 *
 * It keeps nothing itself: the Firebase SDK caches the token and refreshes it when it is about
 * to expire. `forceRefresh` is passed straight through; the network layer sets it once after a
 * 401. Nothing here is logged.
 */
class FirebaseIdTokenProvider @Inject constructor(
    private val gateway: PhoneAuthGateway,
) : IdTokenProvider {
    override suspend fun idToken(forceRefresh: Boolean): String? = gateway.idToken(forceRefresh)
}
