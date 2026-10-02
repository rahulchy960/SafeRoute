// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

/*
 * The Material 3 type scale with the device's own fonts: nothing to download or license, and
 * the system picks a Bengali face (Noto Sans Bengali on most phones) where it is needed.
 *
 * Sizes are in sp, so they follow the user's font-size setting (tested up to 200%).
 */

/**
 * Bengali letters carry marks above and below the line that are taller than Latin letters.
 * `Trim.None` keeps the full line height on the first and last line so those marks are not cut.
 */
private val TallScriptLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun TextStyle.forTallScripts(): TextStyle = copy(lineHeightStyle = TallScriptLineHeight)

internal val SafeRouteTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.forTallScripts(),
        displayMedium = displayMedium.forTallScripts(),
        displaySmall = displaySmall.forTallScripts(),
        headlineLarge = headlineLarge.forTallScripts(),
        headlineMedium = headlineMedium.forTallScripts(),
        headlineSmall = headlineSmall.forTallScripts(),
        titleLarge = titleLarge.forTallScripts(),
        titleMedium = titleMedium.forTallScripts(),
        titleSmall = titleSmall.forTallScripts(),
        bodyLarge = bodyLarge.forTallScripts(),
        bodyMedium = bodyMedium.forTallScripts(),
        bodySmall = bodySmall.forTallScripts(),
        labelLarge = labelLarge.forTallScripts(),
        labelMedium = labelMedium.forTallScripts(),
        labelSmall = labelSmall.forTallScripts(),
    )
}
