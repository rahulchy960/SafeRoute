// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/**
 * The root of the UI, called once from [MainActivity].
 *
 * P007a ships the foundation only, so this shows the app name and the standing notice. P007b
 * replaces the body with the navigation host (Home, Search, Settings).
 */
@Composable
fun SafeRouteApp(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            // safeDrawingPadding keeps content clear of the status bar, navigation bar and
            // display cut-outs now that the app draws edge to edge.
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(SafeRouteTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(
                SafeRouteTheme.spacing.sm,
                Alignment.CenterVertically,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.not_emergency_service),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@SafeRoutePreviews
@Composable
private fun SafeRouteAppPreview() {
    SafeRouteTheme { SafeRouteApp() }
}
