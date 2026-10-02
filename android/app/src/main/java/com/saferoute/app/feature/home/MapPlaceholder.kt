// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

private val GridStep = 48.dp

/**
 * Stands where the map will be until P010 brings MapLibre.
 *
 * In debug builds it draws a faint grid and a label, so that nobody mistakes the grey area for
 * a broken map. In a release build it is an empty neutral surface: users are never shown
 * developer notes.
 *
 * @param showDebugDetails Pass `BuildConfig.DEBUG`.
 */
@Composable
fun MapPlaceholder(showDebugDetails: Boolean, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (showDebugDetails) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = GridStep.toPx()
                var x = step
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
                    y += step
                }
            }
            Text(
                text = stringResource(R.string.map_placeholder_label),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(SafeRouteTheme.spacing.xs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(name = "Debug build", showBackground = true, heightDp = 320)
@Composable
private fun MapPlaceholderDebugPreview() {
    SafeRouteTheme { MapPlaceholder(showDebugDetails = true) }
}

@Preview(name = "Release build", showBackground = true, heightDp = 320)
@Composable
private fun MapPlaceholderReleasePreview() {
    SafeRouteTheme { MapPlaceholder(showDebugDetails = false) }
}
