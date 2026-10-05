// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import com.saferoute.app.core.network.auth.idTokenBlocking
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseIdTokenProviderTest {

    @Test
    fun `null when nobody is signed in`() = runTest {
        val gateway = FakePhoneAuthGateway(signedIn = false)
        val provider = FirebaseIdTokenProvider(gateway)

        assertNull(provider.idToken(forceRefresh = false))
        assertNull(provider.idToken(forceRefresh = true))
    }

    @Test
    fun `the token when signed in, and forceRefresh is passed through`() = runTest {
        val gateway = FakePhoneAuthGateway(signedIn = true)
        val provider = FirebaseIdTokenProvider(gateway)

        assertEquals(FAKE_ID_TOKEN, provider.idToken(forceRefresh = false))
        assertEquals(FAKE_REFRESHED_ID_TOKEN, provider.idToken(forceRefresh = true))
        assertEquals(listOf(false, true), gateway.tokenRequests)
    }

    @Test
    fun `it asks the gateway every time and keeps nothing itself`() = runTest {
        val gateway = FakePhoneAuthGateway(signedIn = true)
        val provider = FirebaseIdTokenProvider(gateway)

        assertEquals(FAKE_ID_TOKEN, provider.idToken(forceRefresh = false))
        gateway.signOut()
        assertNull(provider.idToken(forceRefresh = false))
        assertEquals(2, gateway.tokenRequests.size)
    }

    @Test
    fun `a failed refresh reaches the network layer as an IOException, not a crash`() {
        val gateway = FakePhoneAuthGateway(signedIn = true).apply {
            idTokenFailure = IllegalStateException("secret-sdk-message")
        }
        val provider = FirebaseIdTokenProvider(gateway)

        val thrown = assertThrows(IOException::class.java) { provider.idTokenBlocking(forceRefresh = true) }
        assertEquals("Could not get an ID token", thrown.message)
    }
}
