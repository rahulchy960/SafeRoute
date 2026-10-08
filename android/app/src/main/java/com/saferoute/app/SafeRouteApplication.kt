// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.app.Application
import com.saferoute.app.feature.emergency.EmergencyNotificationController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The process-wide entry point. Android creates exactly one instance before any activity.
 *
 * `@HiltAndroidApp` makes Hilt generate the dependency container that lives as long as the app
 * process; every `@AndroidEntryPoint` and `@HiltViewModel` gets its dependencies from it.
 * The class is registered in the manifest with `android:name`.
 */
@HiltAndroidApp
class SafeRouteApplication : Application() {

    @Inject lateinit var emergencyNotification: EmergencyNotificationController

    override fun onCreate() {
        super.onCreate()
        // From now on the pinned emergency notification follows the user's switch: shown when
        // it is on and allowed, removed when it is turned off or the user signs out. This
        // starts no service and shows nothing unless the user switched it on.
        emergencyNotification.start()
    }
}
