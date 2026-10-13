// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last few things that went wrong, for "Report a problem": a category and a time,
 * nothing else. It lives in memory only. It is gone when the app's process ends, it is
 * never written to storage, and nothing sends it: a person reads it on the screen and
 * decides whether to share it.
 */
@Singleton
class DiagnosticsLog @Inject constructor(private val clock: Clock) {
    data class Entry(val category: String, val at: Instant)

    private val entries = ArrayDeque<Entry>()

    /** Called by the reporter with an event's fixed category; never with other text. */
    @Synchronized
    internal fun add(category: String) {
        if (entries.size == CAPACITY) entries.removeFirst()
        entries.addLast(Entry(category, clock.instant()))
    }

    /** Oldest first. */
    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    /** Called when the session leaves the signed-in states. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val CAPACITY = 20
    }
}
