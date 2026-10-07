// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.SelectedPlace

/**
 * What the sheet shows for a place chosen in search: its name, where it is, a way to close it,
 * and a Directions button that asks for routes from the user's position to this place.
 */
@Composable
internal fun PlaceCard(
    place: SelectedPlace,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDirections: () -> Unit = {},
) {
    val spacing = SafeRouteTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                modifier = Modifier.padding(top = spacing.xxs),
                tint = SafeRouteTheme.colors.place,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text(
                    text = place.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (place.label.isNotBlank()) {
                    Text(
                        text = place.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // IconButton is 48 dp, the minimum touch target.
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.place_card_close),
                )
            }
        }
        // A Material button is at least 48 dp tall with its touch area.
        FilledTonalButton(onClick = onDirections) {
            Text(text = stringResource(R.string.place_card_directions))
        }
    }
}

@SafeRoutePreviews
@Composable
private fun PlaceCardPreview() {
    SafeRouteTheme {
        PlaceCard(
            place = SelectedPlace(
                name = "Main Station",
                label = "Station Road, Example District",
                position = LatLng(10.0, 20.0),
            ),
            onClose = {},
        )
    }
}
