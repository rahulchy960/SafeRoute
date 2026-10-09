// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.security.SecureRandom
import java.time.Clock
import java.util.Random
import java.util.UUID

private const val TIMESTAMP_MASK = (1L shl 48) - 1
private const val VERSION_7 = 0x7000L
private const val RAND_A_MASK = 0x0FFFL
private const val VARIANT_RFC = 1L shl 63
private const val RAND_B_MASK = (1L shl 62) - 1

/**
 * Makes UUIDs of version 7 (RFC 9562): the first 48 bits are the time in milliseconds, the
 * rest is random. Ids made later sort after ids made earlier, which keeps a table ordered by
 * its key, and the server can use the same id as the idempotency key of an emergency (P015).
 *
 * The id says WHEN it was made and nothing else: no device, user or place is in it.
 * Written here because the JDK has no version 7 and a dependency for 20 lines is not worth it.
 */
class UuidV7Generator(
    private val clock: Clock,
    private val random: Random = SecureRandom(),
) {

    fun next(): String {
        val millis = clock.millis() and TIMESTAMP_MASK
        val high = (millis shl 16) or VERSION_7 or (random.nextLong() and RAND_A_MASK)
        val low = VARIANT_RFC or (random.nextLong() and RAND_B_MASK)
        return UUID(high, low).toString()
    }
}
