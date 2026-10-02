// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast ratio of [foreground] drawn on [background], from 1.0 (same colour) to
 * 21.0 (black on white). A translucent foreground is first blended onto the background.
 */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val a = foreground.compositeOver(background).relativeLuminance()
    val b = background.relativeLuminance()
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

private fun Color.relativeLuminance(): Double {
    fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
}

/**
 * Guards the contrast promises of the design system (ADR 0008): body text at least 4.5:1,
 * large text and UI components at least 3:1, in the light and the dark theme.
 *
 * Plain JVM test: no Android framework needed.
 */
class ThemeContrastTest {

    private class Palette(val name: String, val scheme: ColorScheme, val colors: SafeRouteColors)

    private class Pair(val name: String, val foreground: (Palette) -> Color, val background: (Palette) -> Color)

    private val palettes = listOf(
        Palette("light", LightColorScheme, LightSafeRouteColors),
        Palette("dark", DarkColorScheme, DarkSafeRouteColors),
    )

    /** Text and icons that must be readable at body size: at least 4.5:1. */
    private val textPairs = listOf(
        Pair("onSurface / surface", { it.scheme.onSurface }, { it.scheme.surface }),
        Pair("onSurfaceVariant / surface", { it.scheme.onSurfaceVariant }, { it.scheme.surface }),
        Pair("onSurface / surfaceContainerLow (sheet)", { it.scheme.onSurface }, { it.scheme.surfaceContainerLow }),
        Pair("onSurfaceVariant / surfaceContainerLow (sheet)", { it.scheme.onSurfaceVariant }, { it.scheme.surfaceContainerLow }),
        Pair("onSurfaceVariant / surfaceContainerHighest", { it.scheme.onSurfaceVariant }, { it.scheme.surfaceContainerHighest }),
        Pair("onPrimary / primary", { it.scheme.onPrimary }, { it.scheme.primary }),
        Pair("onPrimaryContainer / primaryContainer", { it.scheme.onPrimaryContainer }, { it.scheme.primaryContainer }),
        Pair("onSecondary / secondary", { it.scheme.onSecondary }, { it.scheme.secondary }),
        Pair("onSecondaryContainer / secondaryContainer", { it.scheme.onSecondaryContainer }, { it.scheme.secondaryContainer }),
        Pair("onTertiary / tertiary", { it.scheme.onTertiary }, { it.scheme.tertiary }),
        Pair("onTertiaryContainer / tertiaryContainer", { it.scheme.onTertiaryContainer }, { it.scheme.tertiaryContainer }),
        Pair("onError / error", { it.scheme.onError }, { it.scheme.error }),
        Pair("primary (text button) / surface", { it.scheme.primary }, { it.scheme.surface }),
        Pair("onSos / sos", { it.colors.onSos }, { it.colors.sos }),
        Pair("onSosContainer / sosContainer", { it.colors.onSosContainer }, { it.colors.sosContainer }),
        Pair("onCaution / caution", { it.colors.onCaution }, { it.colors.caution }),
        Pair("onCautionContainer / cautionContainer", { it.colors.onCautionContainer }, { it.colors.cautionContainer }),
        Pair("onPositive / positive", { it.colors.onPositive }, { it.colors.positive }),
        Pair("onPositiveContainer / positiveContainer", { it.colors.onPositiveContainer }, { it.colors.positiveContainer }),
        Pair("onMapOverlay / mapOverlay", { it.colors.onMapOverlay }, { it.colors.mapOverlay }),
        Pair("mapOverlayVariant / mapOverlay", { it.colors.mapOverlayVariant }, { it.colors.mapOverlay }),
    )

    /** Shapes that must be told apart from what is behind them: at least 3:1. */
    private val componentPairs = listOf(
        Pair("primary / surface", { it.scheme.primary }, { it.scheme.surface }),
        Pair("tertiary / surface", { it.scheme.tertiary }, { it.scheme.surface }),
        Pair("outline / surface", { it.scheme.outline }, { it.scheme.surface }),
        Pair("outline (sheet handle) / surfaceContainerLow", { it.scheme.outline }, { it.scheme.surfaceContainerLow }),
        Pair("outline / mapOverlay", { it.scheme.outline }, { it.colors.mapOverlay }),
        Pair("sos (emergency button) / surface", { it.colors.sos }, { it.scheme.surface }),
        Pair("sos (emergency button) / surfaceContainerLow", { it.colors.sos }, { it.scheme.surfaceContainerLow }),
        Pair("caution / surface", { it.colors.caution }, { it.scheme.surface }),
        Pair("positive / surface", { it.colors.positive }, { it.scheme.surface }),
    )

    @Test
    fun `helper matches known WCAG values`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.001)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
        // #767676 on white is the textbook "just passes AA" grey.
        assertEquals(4.54, contrastRatio(Color(0xFF767676), Color.White), 0.01)
        // Half-transparent black on white is blended to mid grey before measuring.
        assertTrue(contrastRatio(Color.Black.copy(alpha = 0.5f), Color.White) < 21.0)
    }

    @Test
    fun `text pairs reach 4_5 to 1 in both themes`() = assertAll(textPairs, minimum = 4.5)

    @Test
    fun `component pairs reach 3 to 1 in both themes`() = assertAll(componentPairs, minimum = 3.0)

    @Test
    fun `sos red is the same family in both themes and differs from Material error`() {
        // The emergency colour must not silently become the generic error colour.
        palettes.forEach { p ->
            assertTrue("${p.name}: sos must not equal error", p.colors.sos != p.scheme.error)
            assertTrue("${p.name}: sos must be red-dominant", p.colors.sos.red > p.colors.sos.green)
            assertTrue("${p.name}: sos must be red-dominant", p.colors.sos.red > p.colors.sos.blue)
        }
    }

    private fun assertAll(pairs: List<Pair>, minimum: Double) {
        val failures = buildList {
            for (palette in palettes) {
                for (pair in pairs) {
                    val ratio = contrastRatio(pair.foreground(palette), pair.background(palette))
                    println("contrast ${palette.name}: ${pair.name} = %.2f:1".format(ratio))
                    if (ratio < minimum) add("${palette.name}: ${pair.name} = %.2f:1".format(ratio))
                }
            }
        }
        assertTrue("Below $minimum:1 -> $failures", failures.isEmpty())
    }
}
