// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.feature.onboarding.OnboardingViewModel
import com.saferoute.app.navigation.OnboardingNavHost
import com.saferoute.app.navigation.SafeRouteNavHost

/**
 * The root of the UI, called once from [MainActivity]: a themed background and one of two
 * navigation hosts.
 *
 * The session state is the gate (ADR 0012). Only [SessionState.Ready] shows the app itself
 * (Home, Search, Settings); every other state shows an onboarding screen. Home is therefore
 * unreachable before the age declaration, the consent notice, sign-in and account creation
 * are done. Signing out changes the state, and the app's screens are replaced by onboarding
 * again, back stack included.
 */
@Composable
fun SafeRouteApp(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val welcomeSeen by viewModel.welcomeSeen.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (state == SessionState.Ready) {
            SafeRouteNavHost()
        } else {
            OnboardingNavHost(state = state, welcomeSeen = welcomeSeen, viewModel = viewModel)
        }
    }
}
