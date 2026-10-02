// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import kotlinx.serialization.Serializable

/*
 * The screens the app can show. Each destination is a Kotlin object instead of a text route
 * such as "search": a typing mistake becomes a compile error, and a destination that needs
 * arguments later becomes a data class whose properties are the arguments.
 *
 * `@Serializable` lets Navigation save the destination and restore it after the system has
 * stopped the app in the background.
 */

/** The map screen. The app starts here. */
@Serializable
data object HomeDestination

/** Place search. */
@Serializable
data object SearchDestination

/** Settings and the About section. */
@Serializable
data object SettingsDestination
