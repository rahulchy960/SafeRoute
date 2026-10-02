// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.saferoute.app.feature.developer.DeveloperCheckRoute
import com.saferoute.app.feature.developer.DeveloperSettingsRow
import kotlinx.serialization.Serializable

/*
 * Debug builds only. Gradle compiles src/debug into debug builds and src/release into release
 * builds; both define the same two functions, so SafeRouteNavHost (in src/main) calls them
 * without knowing which build it is in. Here they add the developer tools; the release versions
 * (src/release/.../DeveloperNavigation.kt) add nothing.
 */

/** The server check screen. */
@Serializable
data object DeveloperCheckDestination

/** Adds the developer screens to the navigation graph. */
fun NavGraphBuilder.developerDestinations(navController: NavHostController) {
    composable<DeveloperCheckDestination> {
        DeveloperCheckRoute(onBack = { navController.popBackStack() })
    }
}

/** The "Developer" row on the Settings screen. */
@Composable
fun DeveloperSettingsEntry(navController: NavHostController) {
    DeveloperSettingsRow(
        onClick = { navController.navigate(DeveloperCheckDestination) { launchSingleTop = true } },
    )
}
