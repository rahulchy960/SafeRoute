// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertTrue

/**
 * Waits until the screen and the app's background work are at rest, then requires
 * [condition]. It replaces "poll for up to five seconds" (P014e): there is no time limit to
 * miss on a slow machine, and a condition that is not met fails at once instead of late.
 *
 * "At rest" covers the main thread, Compose, and in a Hilt test the work counted by
 * `BackgroundWork` (the app-lifetime scope, the dispatchers, Room). It does not cover a
 * real network call or a coroutine that sleeps: a test of those says how it waits.
 */
fun ComposeTestRule.settled(what: String = "the expected state once the app is at rest", condition: () -> Boolean) {
    waitForIdle()
    assertTrue(what, condition())
}
