// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/*
 * Release builds have no developer tools. These stand in for the debug versions in
 * src/debug/.../DeveloperNavigation.kt and do nothing, so the debug screens, their strings and
 * their ViewModel are not compiled into a release build at all.
 */

@Suppress("UnusedReceiverParameter", "unused")
fun NavGraphBuilder.developerDestinations(navController: NavHostController) = Unit

@Suppress("unused")
@Composable
fun DeveloperSettingsEntry(navController: NavHostController) = Unit
