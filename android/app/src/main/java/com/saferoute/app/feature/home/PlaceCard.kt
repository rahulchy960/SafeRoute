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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.SelectedPlace

/**
 * What the sheet shows for a place chosen in search: its name, where it is, a way to close it,
 * and a Directions button that does nothing yet.
 *
 * The button is disabled, and its description says in words that directions come later: a
 * greyed-out button alone would be a colour-only signal. Routes arrive with P012.
 */
@Composable
internal fun PlaceCard(place: SelectedPlace, onClose: () -> Unit, modifier: Modifier = Modifier) {
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
        val directionsLater = stringResource(R.string.place_card_directions_unavailable)
        FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.semantics { contentDescription = directionsLater },
        ) {
            Text(text = stringResource(R.string.place_card_directions))
        }
        Text(
            text = stringResource(R.string.coming_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
