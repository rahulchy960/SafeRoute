// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Material 3 colour schemes for SafeRoute.
 *
 * Surfaces are neutral greys with a slight cool tint so that the map, and later the safety
 * overlays on it, are the most colourful things on screen. There is one accent family (a cool
 * teal, `primary`) and a muted indigo `tertiary`. Red is not in this file except as Material's
 * `error` role: the emergency red lives in SafeRouteColors.sos.
 *
 * Contrast is checked by ThemeContrastTest: text pairs >= 4.5:1, UI components >= 3:1, in both
 * themes. Change a value here and that test tells you if a pair fell below the limit.
 */

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006879),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9EDFF),
    onPrimaryContainer = Color(0xFF001F26),
    inversePrimary = Color(0xFF54D7F3),
    secondary = Color(0xFF4B6268),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE7EE),
    onSecondaryContainer = Color(0xFF061F24),
    tertiary = Color(0xFF565D7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE1FF),
    onTertiaryContainer = Color(0xFF121A37),
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFAFBFC),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDCE3E6),
    onSurfaceVariant = Color(0xFF40484B),
    surfaceTint = Color(0xFF006879),
    inverseSurface = Color(0xFF2E3132),
    inverseOnSurface = Color(0xFFEFF1F2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF70787B),
    outlineVariant = Color(0xFFC0C8CB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAFBFC),
    surfaceDim = Color(0xFFD9DBDC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F6),
    surfaceContainer = Color(0xFFEDEFF0),
    surfaceContainerHigh = Color(0xFFE7E9EA),
    surfaceContainerHighest = Color(0xFFE1E3E4),
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF54D7F3),
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF004E5C),
    onPrimaryContainer = Color(0xFFA9EDFF),
    inversePrimary = Color(0xFF006879),
    secondary = Color(0xFFB2CBD2),
    onSecondary = Color(0xFF1D3439),
    secondaryContainer = Color(0xFF334A50),
    onSecondaryContainer = Color(0xFFCEE7EE),
    tertiary = Color(0xFFBEC5EB),
    onTertiary = Color(0xFF282F4D),
    tertiaryContainer = Color(0xFF3F4565),
    onTertiaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF111415),
    onBackground = Color(0xFFE1E3E4),
    surface = Color(0xFF111415),
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF40484B),
    onSurfaceVariant = Color(0xFFC0C8CB),
    surfaceTint = Color(0xFF54D7F3),
    inverseSurface = Color(0xFFE1E3E4),
    inverseOnSurface = Color(0xFF2E3132),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A9295),
    outlineVariant = Color(0xFF40484B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF373A3B),
    surfaceDim = Color(0xFF111415),
    surfaceContainerLowest = Color(0xFF0C0F10),
    surfaceContainerLow = Color(0xFF191C1D),
    surfaceContainer = Color(0xFF1D2021),
    surfaceContainerHigh = Color(0xFF272A2B),
    surfaceContainerHighest = Color(0xFF323536),
)
