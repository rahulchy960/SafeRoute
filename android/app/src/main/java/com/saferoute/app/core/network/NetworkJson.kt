// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.generated.infrastructure.OffsetDateTimeAdapter
import com.saferoute.app.core.network.generated.infrastructure.UUIDAdapter
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.modules.SerializersModule

/**
 * The generator types a JSON `number` (a latitude, a longitude) as `BigDecimal` and expects the
 * app to say how to read one. A JSON number is read as a double; a text in its place is an error.
 */
internal object BigDecimalAsNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimalAsNumber", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): BigDecimal {
        // decodeDouble() alone would also accept "10.5" in quotes. Look at the JSON itself.
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        val number = element?.takeUnless { it.isString }?.doubleOrNull
            ?: throw SerializationException("expected a JSON number")
        return BigDecimal.valueOf(number)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeDouble(value.toDouble())
}

/**
 * How JSON is read and written for the API (ADR 0004, ADR 0009).
 *
 * - `ignoreUnknownKeys`: a newer backend may add fields; an older app must not fail on them.
 * - `explicitNulls = false`: an absent optional field is sent as absent, not as `null`.
 * - `coerceInputValues` and `isLenient` stay off: malformed input is an error, not a guess.
 * - Two serializers are the generator's own, for `format: uuid` and `format: date-time`; the
 *   third reads plain JSON numbers (coordinates, since P011b).
 */
internal val networkJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = false
    explicitNulls = false
    isLenient = false
    serializersModule = SerializersModule {
        contextual(UUID::class, UUIDAdapter)
        contextual(OffsetDateTime::class, OffsetDateTimeAdapter)
        contextual(BigDecimal::class, BigDecimalAsNumberSerializer)
    }
}
