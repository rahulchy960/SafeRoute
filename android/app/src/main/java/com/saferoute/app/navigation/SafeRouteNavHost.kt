// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.saferoute.app.BuildConfig
import com.saferoute.app.feature.contacts.AddContactRoute
import com.saferoute.app.feature.contacts.ContactDetailRoute
import com.saferoute.app.feature.contacts.ContactsRoute
import com.saferoute.app.feature.contacts.InviteRoute
import com.saferoute.app.feature.home.HomeRoute
import com.saferoute.app.feature.search.SearchRoute
import com.saferoute.app.feature.settings.SettingsRoute

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
                onOpenContacts = {
                    navController.navigate(ContactsDestination) { launchSingleTop = true }
                },
            )
        }
        composable<SearchDestination> {
            SearchRoute(
                onBack = { navController.popBackStack() },
                // The chosen place is already with the map (MapSelection); Home shows it.
                onPlaceChosen = { navController.popBackStack(HomeDestination, inclusive = false) },
            )
        }
        composable<SettingsDestination> {
            SettingsRoute(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                onBack = { navController.popBackStack() },
                onOpenContacts = {
                    navController.navigate(ContactsDestination) { launchSingleTop = true }
                },
                // A row in debug builds, nothing in release builds.
                developerEntry = { DeveloperSettingsEntry(navController) },
            )
        }
        composable<ContactsDestination> {
            ContactsRoute(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(AddContactDestination) { launchSingleTop = true } },
                onOpenContact = { id ->
                    navController.navigate(ContactDetailDestination(id)) { launchSingleTop = true }
                },
            )
        }
        composable<AddContactDestination> {
            AddContactRoute(
                onBack = { navController.popBackStack() },
                // The form is done: the invite takes its place, so back leads to the list.
                onSaved = { id ->
                    navController.navigate(InviteDestination(id)) {
                        popUpTo<AddContactDestination> { inclusive = true }
                    }
                },
            )
        }
        composable<ContactDetailDestination> {
            ContactDetailRoute(
                onBack = { navController.popBackStack() },
                onInvite = { id -> navController.navigate(InviteDestination(id)) { launchSingleTop = true } },
            )
        }
        composable<InviteDestination> {
            InviteRoute(onDone = { navController.popBackStack() })
        }
        // Debug builds only: the server check screen. Release builds add nothing.
        developerDestinations(navController)
    }
}
