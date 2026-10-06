// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.saferoute.app.BuildConfig
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.feature.onboarding.AgeGateScreen
import com.saferoute.app.feature.onboarding.BlockedScreen
import com.saferoute.app.feature.onboarding.ConsentNoticeScreen
import com.saferoute.app.feature.onboarding.OnboardingViewModel
import com.saferoute.app.feature.onboarding.ProblemScreen
import com.saferoute.app.feature.onboarding.SignInRoute
import com.saferoute.app.feature.onboarding.WelcomeScreen
import com.saferoute.app.feature.onboarding.WorkingScreen
import kotlinx.serialization.Serializable

/*
 * The screens before Home. Which one shows is decided by the session state, not by the screen
 * the person came from: `onboardingDestinationFor` below is the whole rule.
 */

/** A spinner: the session is being checked, or the account is being created. */
@Serializable
data object OnboardingWorkingDestination

@Serializable
data object OnboardingWelcomeDestination

@Serializable
data object OnboardingAgeDestination

@Serializable
data object OnboardingNoticeDestination

/** Phone number and SMS code. */
@Serializable
data object OnboardingSignInDestination

/** The app cannot be used (under 18, or the account was deleted). */
@Serializable
data object OnboardingBlockedDestination

/** The session check failed; offers "Try again". */
@Serializable
data object OnboardingProblemDestination

/**
 * The onboarding screen for a session state. Order: welcome → age → consent → phone
 * (CLAUDE.md "Android rules").
 *
 * @param welcomeSeen null while the stored flags are still being read.
 * @return null for [SessionState.Ready]: onboarding is over and Home is shown.
 */
fun onboardingDestinationFor(state: SessionState, welcomeSeen: Boolean?): Any? = when (state) {
    SessionState.Loading, SessionState.NeedsBootstrap -> OnboardingWorkingDestination
    SessionState.NeedsAge -> when (welcomeSeen) {
        null -> OnboardingWorkingDestination
        false -> OnboardingWelcomeDestination
        true -> OnboardingAgeDestination
    }
    SessionState.NeedsConsent -> OnboardingNoticeDestination
    SessionState.SignedOut -> OnboardingSignInDestination
    is SessionState.Blocked -> OnboardingBlockedDestination
    is SessionState.Error -> OnboardingProblemDestination
    SessionState.Ready -> null
}

/**
 * Shows the onboarding screen that belongs to [state] and follows it as it changes.
 *
 * When the state changes, the new screen replaces the old one (`popUpTo` the whole graph), so
 * the back stack always holds exactly one onboarding screen. Back therefore leaves the app; it
 * never returns to a step that is already done (the age answer, say, is already recorded).
 *
 * `LaunchedEffect(target)` runs its block each time `target` changes.
 */
@Composable
fun OnboardingNavHost(
    state: SessionState,
    welcomeSeen: Boolean?,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val target = onboardingDestinationFor(state, welcomeSeen) ?: return
    // Start on the right screen straight away, so nothing else flashes first.
    val start = remember { target }

    NavHost(navController = navController, startDestination = start, modifier = modifier) {
        composable<OnboardingWorkingDestination> {
            WorkingScreen(creatingAccount = state == SessionState.NeedsBootstrap)
        }
        composable<OnboardingWelcomeDestination> {
            WelcomeScreen(onContinue = viewModel::onWelcomeContinue)
        }
        composable<OnboardingAgeDestination> {
            AgeGateScreen(
                onAdult = viewModel::onAdultConfirmed,
                onUnder18Confirmed = viewModel::onUnder18Confirmed,
            )
        }
        composable<OnboardingNoticeDestination> {
            ConsentNoticeScreen(
                showDraftMarker = BuildConfig.DEBUG,
                onAgree = viewModel::onNoticeAccepted,
            )
        }
        composable<OnboardingSignInDestination> {
            SignInRoute()
        }
        composable<OnboardingBlockedDestination> {
            // While this screen is animating away the state may already be something else.
            BlockedScreen(reason = (state as? SessionState.Blocked)?.reason ?: BlockReason.UNDER_18)
        }
        composable<OnboardingProblemDestination> {
            ProblemScreen(
                retryable = (state as? SessionState.Error)?.retryable ?: true,
                onRetry = viewModel::refresh,
            )
        }
    }

    // After NavHost, which gives the controller its graph. Nothing to do while the screen for
    // this state is already the one on show (the first composition, for example).
    LaunchedEffect(target) {
        if (navController.currentDestination?.hasRoute(target::class) == true) return@LaunchedEffect
        navController.navigate(target) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}
