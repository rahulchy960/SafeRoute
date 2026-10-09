// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the phone's copy of the contacts in step with the session.
 *
 * - **Signed in and ready** → fetch the list in the background. Nothing waits for it: Home
 *   opens at once, and a failed fetch leaves the copy as it was.
 * - **Signed out, back at the start, or blocked** → empty the copy. The contacts belong to the
 *   account that was signed in; nobody else who uses this phone may see them.
 *
 * It follows the session's state instead of being called from "sign out", so it also catches a
 * sign-out the server forced (an expired sign-in, a deleted account), and it heals itself: if
 * the app was killed before the copy was emptied, the next start finds the same state and
 * empties it then.
 *
 * Runs in the app-lifetime scope: its work must not end with a screen.
 */
@Singleton
class ContactsSessionSync @Inject constructor(
    private val session: Session,
    private val repository: ContactsRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Starts listening. Calling it again does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            // A StateFlow emits only when the value changes, so each arrival here is a real
            // change of state (plus the value at the moment of starting).
            session.state.collect { state ->
                when (state) {
                    // Its own coroutine: a slow fetch must not delay the reaction to a sign-out.
                    SessionState.Ready -> launch {
                        repository.refresh()
                        // Also brings the "agreed to the alerts notice" flag to this phone, for
                        // example after a reinstall; a failure leaves the flag as it was.
                        repository.hasConsent()
                    }

                    SessionState.SignedOut,
                    SessionState.NeedsAge,
                    is SessionState.Blocked,
                    -> repository.clearLocal()

                    // Loading, an error, or a signed-in user who is asked to accept a newer
                    // notice: nothing is known for sure, so the copy stays.
                    SessionState.Loading,
                    SessionState.NeedsConsent,
                    SessionState.NeedsBootstrap,
                    is SessionState.Error,
                    -> Unit
                }
            }
        }
    }
}
