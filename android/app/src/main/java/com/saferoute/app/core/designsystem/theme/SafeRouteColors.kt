// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours that carry a meaning Material's roles don't have. Each fill colour comes with the
 * colour for text and icons drawn on it (`on…`).
 *
 * Rules (ADR 0008):
 * - [sos] and its relatives are used **only** for the emergency button and the emergency
 *   dialog, never as decoration. `SosColourUsageTest` enforces this.
 * - Colour is never the only signal: pair it with an icon or text.
 * - These values do not change with Material You (dynamic colour), so "red means emergency"
 *   stays true on every phone.
 *
 * Read them with `SafeRouteTheme.colors`.
 *
 * @property sos Emergency red, for SOS and 112 actions.
 * @property caution Amber, for conditions worth attention (used from P019).
 * @property positive Green, for confirmations such as "sent" or "you are sharing".
 * @property mapOverlay Background of controls that float on the map (search pill, map buttons).
 * @property mapOverlayVariant Text and icons of lower emphasis on [mapOverlay].
 * @property scrim Translucent layer that dims the map behind a sheet or dialog.
 */
@Immutable
data class SafeRouteColors(
    val sos: Color,
    val onSos: Color,
    val sosContainer: Color,
    val onSosContainer: Color,
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val positive: Color,
    val onPositive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val mapOverlay: Color,
    val onMapOverlay: Color,
    val mapOverlayVariant: Color,
    /** The current-location dot on the map. Blue: never the SOS red, never a "safe" green. */
    val location: Color,
    /** An old position. */
    val locationStale: Color,
    val scrim: Color,
    val onScrim: Color,
)

internal val LightSafeRouteColors = SafeRouteColors(
    sos = Color(0xFFB3261E),
    onSos = Color(0xFFFFFFFF),
    sosContainer = Color(0xFFFFDAD6),
    onSosContainer = Color(0xFF410002),
    caution = Color(0xFF7C5800),
    onCaution = Color(0xFFFFFFFF),
    cautionContainer = Color(0xFFFFDEA6),
    onCautionContainer = Color(0xFF271900),
    positive = Color(0xFF1E6B33),
    onPositive = Color(0xFFFFFFFF),
    positiveContainer = Color(0xFFA8F5B0),
    onPositiveContainer = Color(0xFF002108),
    mapOverlay = Color(0xFFFFFFFF),
    onMapOverlay = Color(0xFF191C1D),
    mapOverlayVariant = Color(0xFF40484B),
    location = Color(0xFF0B57D0),
    locationStale = Color(0xFF6F7578),
    scrim = Color(0x52000000),
    onScrim = Color(0xFFFFFFFF),
)

internal val DarkSafeRouteColors = SafeRouteColors(
    sos = Color(0xFFFF8A80),
    onSos = Color(0xFF3B0003),
    sosContainer = Color(0xFF93000A),
    onSosContainer = Color(0xFFFFDAD6),
    caution = Color(0xFFF5BF48),
    onCaution = Color(0xFF412D00),
    cautionContainer = Color(0xFF5E4200),
    onCautionContainer = Color(0xFFFFDEA6),
    positive = Color(0xFF8CD896),
    onPositive = Color(0xFF00390F),
    positiveContainer = Color(0xFF005319),
    onPositiveContainer = Color(0xFFA8F5B0),
    mapOverlay = Color(0xFF272A2B),
    onMapOverlay = Color(0xFFE1E3E4),
    mapOverlayVariant = Color(0xFFC0C8CB),
    location = Color(0xFF8AB4F8),
    locationStale = Color(0xFF9DA3A6),
    scrim = Color(0x99000000),
    onScrim = Color(0xFFFFFFFF),
)

/**
 * A CompositionLocal passes a value down the UI tree without threading it through every
 * function. `static` because the value changes rarely (only when the theme flips).
 */
internal val LocalSafeRouteColors = staticCompositionLocalOf { LightSafeRouteColors }
