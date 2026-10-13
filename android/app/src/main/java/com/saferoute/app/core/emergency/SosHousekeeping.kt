// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the emergency records on the phone no longer than promised:
 *
 * - **Every app start, and once a day**: records and points older than [SOS_RETENTION] are
 *   deleted.
 * - **Signed out, back at the start, or blocked**: everything is deleted. The records belong
 *   to the account that was signed in. **Not while an SOS is going on**: an emergency that
 *   is running when the account is blocked, deleted or signed out by the server is allowed
 *   to finish on the phone, and the wipe follows when the user cancelled it or said "I'm
 *   safe" (P014c3). If the process dies in between, the next start waits the same way.
 *
 * Like `ContactsSessionSync` it follows the session's state, so it also catches a sign-out
 * the server forced, and a wipe the process did not live to finish happens at the next start.
 * Runs in the app-lifetime scope.
 */
/** Plans the daily clean-up for phones on which the app is rarely opened (WorkManager). */
fun interface PurgeScheduler {
    fun scheduleDaily()
}

@Singleton
class SosHousekeeping @Inject constructor(
    private val session: Session,
    private val store: SosStore,
    private val engine: SosEngine,
    private val clock: Clock,
    private val scheduler: PurgeScheduler,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Starts listening. Calling it again does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch { purgeExpired() }
        scheduler.scheduleDaily()
        appScope.launch {
            // collectLatest: a wipe that is still waiting for an SOS to end is dropped when
            // the session changes again (the same person signed in again, for example).
            session.state.collectLatest { state ->
                when (state) {
                    SessionState.SignedOut,
                    SessionState.NeedsAge,
                    is SessionState.Blocked,
                    -> {
                        engine.awaitNoEmergency()
                        store.wipe()
                    }

                    SessionState.Loading,
                    SessionState.NeedsConsent,
                    SessionState.NeedsBootstrap,
                    SessionState.Ready,
                    is SessionState.Error,
                    -> Unit
                }
            }
        }
    }

    suspend fun purgeExpired() = store.purgeBefore(clock.instant().minus(SOS_RETENTION))
}
