// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The process-wide entry point. Android creates exactly one instance before any activity.
 *
 * `@HiltAndroidApp` makes Hilt generate the dependency container that lives as long as the app
 * process; every `@AndroidEntryPoint` and `@HiltViewModel` gets its dependencies from it.
 * The class is registered in the manifest with `android:name`.
 */
@HiltAndroidApp
class SafeRouteApplication : Application()
