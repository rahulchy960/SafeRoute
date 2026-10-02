// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.saferoute.app.BuildConfig
import com.saferoute.app.feature.home.HomeRoute
import com.saferoute.app.feature.search.SearchScreen
import com.saferoute.app.feature.settings.SettingsScreen

/**
 * Maps each destination to its screen and owns the back stack.
 *
 * The back stack is the pile of screens the person has opened: `navigate` puts one on top,
 * back takes the top one off, and back on the last one (Home) leaves the app. The system back
 * gesture, including the predictive back preview, is handled by NavHost; there is no custom
 * back code.
 *
 * Screens don't know about navigation. They get callbacks such as `onOpenSearch`, so they can
 * be previewed and tested alone.
 */
@Composable
fun SafeRouteNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = HomeDestination,
        modifier = modifier,
    ) {
        composable<HomeDestination> {
            HomeRoute(
                // launchSingleTop: a double tap must not open the same screen twice.
                onOpenSearch = {
                    navController.navigate(SearchDestination) { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate(SettingsDestination) { launchSingleTop = true }
                },
            )
        }
        composable<SearchDestination> {
            SearchScreen(onBack = { navController.popBackStack() })
        }
        composable<SettingsDestination> {
            SettingsScreen(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
