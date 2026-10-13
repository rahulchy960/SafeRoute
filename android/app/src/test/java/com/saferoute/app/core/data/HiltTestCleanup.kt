// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.core.di.BackgroundWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * What a Hilt test opened and must give back: its in-memory database and the app-lifetime
 * scope that works on it. `TestDatabaseModule` notes both here; [HiltTestCleanup] releases them.
 *
 * Without this, each test left a database open (the "failed to call close" warnings came
 * from the garbage collector finding them) and its app-lifetime coroutines running into
 * the tests after it.
 */
object TestResources {

    private val scopes = mutableListOf<CoroutineScope>()
    private val databases = mutableListOf<SafeRouteDatabase>()

    @Synchronized
    fun track(database: SafeRouteDatabase, scope: CoroutineScope) {
        databases += database
        scopes += scope
    }

    /** How many databases are open right now; a test of the rule reads it. */
    @Synchronized
    fun openDatabases(): Int = databases.count { it.isOpen }

    /**
     * Stops the work first and closes the database second: a coroutine that still ran would
     * otherwise meet a closed database. The wait has a limit only so that work which ignores
     * cancellation cannot hang the whole run; nothing is asserted about it.
     */
    @Synchronized
    fun release() {
        scopes.forEach { it.cancel() }
        runBlocking {
            scopes.forEach { scope -> withTimeoutOrNull(STOP_LIMIT_MILLIS) { scope.coroutineContext.job.join() } }
        }
        scopes.clear()
        databases.forEach { it.close() }
        databases.clear()
        BackgroundWork.end()
    }

    private const val STOP_LIMIT_MILLIS = 10_000L
}

/**
 * The rule every Hilt test declares next to its `HiltAndroidRule`, with an order below the
 * Compose rule's so that the screen is gone before the database closes.
 * `HiltTestCleanupTest` fails when a Hilt test forgets it.
 */
class HiltTestCleanup : ExternalResource() {
    override fun after() = TestResources.release()
}
