// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saferoute.app.navigation.SafeRouteNavHost

/**
 * The root of the UI, called once from [MainActivity]: a themed background and the navigation
 * host that shows one screen at a time (Home, Search or Settings).
 */
@Composable
fun SafeRouteApp(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        SafeRouteNavHost()
    }
}
