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
import kotlinx.coroutines.launch

/**
 * Keeps the emergency records on the phone no longer than promised:
 *
 * - **Every app start, and once a day**: records and points older than [SOS_RETENTION] are
 *   deleted.
 * - **Signed out, back at the start, or blocked**: everything is deleted. The records belong
 *   to the account that was signed in.
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
            session.state.collect { state ->
                when (state) {
                    SessionState.SignedOut,
                    SessionState.NeedsAge,
                    is SessionState.Blocked,
                    -> store.wipe()

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
