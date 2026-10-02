// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Use these instead of ad-hoc dp values so that gaps stay consistent.
 * Read with `SafeRouteTheme.spacing`.
 */
@Immutable
data class SafeRouteSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
)

/**
 * Shadow depths. Things that float on the map use [raised] or [floating]; sheets use [sheet].
 * Read with `SafeRouteTheme.elevation`.
 */
@Immutable
data class SafeRouteElevation(
    val none: Dp = 0.dp,
    val low: Dp = 1.dp,
    val raised: Dp = 3.dp,
    val floating: Dp = 6.dp,
    val sheet: Dp = 8.dp,
)

/**
 * The smallest size of anything a finger must hit (Android accessibility guidance). Every
 * interactive component in the design system is at least this big in both directions.
 */
val MinTouchTarget: Dp = 48.dp

internal val LocalSafeRouteSpacing = staticCompositionLocalOf { SafeRouteSpacing() }
internal val LocalSafeRouteElevation = staticCompositionLocalOf { SafeRouteElevation() }
