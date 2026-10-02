// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.generated.infrastructure.OffsetDateTimeAdapter
import com.saferoute.app.core.network.generated.infrastructure.UUIDAdapter
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * How JSON is read and written for the API (ADR 0004, ADR 0009).
 *
 * - `ignoreUnknownKeys`: a newer backend may add fields; an older app must not fail on them.
 * - `explicitNulls = false`: an absent optional field is sent as absent, not as `null`.
 * - `coerceInputValues` and `isLenient` stay off: malformed input is an error, not a guess.
 * - The two serializers are the generator's own, for `format: uuid` and `format: date-time`.
 */
internal val networkJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = false
    explicitNulls = false
    isLenient = false
    serializersModule = SerializersModule {
        contextual(UUID::class, UUIDAdapter)
        contextual(OffsetDateTime::class, OffsetDateTimeAdapter)
    }
}
