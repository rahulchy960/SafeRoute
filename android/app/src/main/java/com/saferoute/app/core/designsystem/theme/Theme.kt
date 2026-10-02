// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * The SafeRoute look: wrap any UI in this and Material components pick up the colours, type
 * and shapes below; SafeRoute's own tokens are available through the [SafeRouteTheme] object.
 *
 * @param darkTheme Follows the system setting unless a caller (a preview, a test) overrides it.
 * @param dynamicColor Material You: take colours from the user's wallpaper (Android 12+). **Off
 *   by default** so that the brand accent and the meaning of the semantic colours are the same
 *   on every phone (ADR 0008). Even when on, [SafeRouteColors] never changes.
 */
@Composable
fun SafeRouteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalSafeRouteColors provides if (darkTheme) DarkSafeRouteColors else LightSafeRouteColors,
        LocalSafeRouteSpacing provides SafeRouteSpacing(),
        LocalSafeRouteElevation provides SafeRouteElevation(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SafeRouteTypography,
            shapes = SafeRouteShapes,
            content = content,
        )
    }
}

/**
 * Access to SafeRoute's own design tokens, in the same style as `MaterialTheme.colorScheme`:
 * `SafeRouteTheme.colors.sos`, `SafeRouteTheme.spacing.md`, `SafeRouteTheme.elevation.floating`.
 */
object SafeRouteTheme {
    val colors: SafeRouteColors
        @Composable @ReadOnlyComposable get() = LocalSafeRouteColors.current

    val spacing: SafeRouteSpacing
        @Composable @ReadOnlyComposable get() = LocalSafeRouteSpacing.current

    val elevation: SafeRouteElevation
        @Composable @ReadOnlyComposable get() = LocalSafeRouteElevation.current
}
