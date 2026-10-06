// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import com.saferoute.app.core.session.AccountResult
import com.saferoute.app.core.session.AccountSummary
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionFlags
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.feature.settings.AccountUiState
import com.saferoute.app.feature.settings.AccountViewModel
import com.saferoute.app.navigation.OnboardingAgeDestination
import com.saferoute.app.navigation.OnboardingBlockedDestination
import com.saferoute.app.navigation.OnboardingNoticeDestination
import com.saferoute.app.navigation.OnboardingProblemDestination
import com.saferoute.app.navigation.OnboardingSignInDestination
import com.saferoute.app.navigation.OnboardingWelcomeDestination
import com.saferoute.app.navigation.OnboardingWorkingDestination
import com.saferoute.app.navigation.onboardingDestinationFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The root ViewModel, the Settings account ViewModel and the state-to-screen rule. */
@OptIn(ExperimentalCoroutinesApi::class) // setMain/resetMain, test code only
class OnboardingViewModelTest {

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun `it checks the session once when it is created`() = runTest {
        val session = FakeSession().apply { afterRefresh = SessionState.NeedsAge }
        val viewModel = OnboardingViewModel(session)

        assertEquals(listOf("refresh"), session.calls)
        assertEquals(SessionState.NeedsAge, viewModel.state.value)
        assertEquals(false, viewModel.welcomeSeen.value)
    }

    @Test
    fun `each onboarding action reaches the session, in order`() = runTest {
        val session = FakeSession(SessionState.NeedsAge)
        val viewModel = OnboardingViewModel(session)

        viewModel.onWelcomeContinue()
        assertEquals(true, viewModel.welcomeSeen.value)

        viewModel.onAdultConfirmed()
        assertEquals(SessionState.NeedsConsent, viewModel.state.value)

        viewModel.onNoticeAccepted("bn")
        assertEquals(SessionState.SignedOut, viewModel.state.value)

        assertEquals(
            listOf("refresh", "markWelcomeSeen", "confirmAdult", "acceptNotice(bn)"),
            session.calls,
        )
    }

    @Test
    fun `under 18 blocks the app`() = runTest {
        val session = FakeSession(SessionState.NeedsAge)
        val viewModel = OnboardingViewModel(session)

        viewModel.onUnder18Confirmed()

        assertEquals(SessionState.Blocked(BlockReason.UNDER_18), viewModel.state.value)
        assertEquals(listOf("refresh", "declareUnder18"), session.calls)
    }

    @Test
    fun `try again runs the check again`() = runTest {
        val session = FakeSession(SessionState.Error(retryable = true))
        val viewModel = OnboardingViewModel(session)
        session.afterRefresh = SessionState.Ready

        viewModel.refresh()

        assertEquals(SessionState.Ready, viewModel.state.value)
        assertEquals(listOf("refresh", "refresh"), session.calls)
    }

    @Test
    fun `each session state has exactly one onboarding screen, and Ready has none`() {
        assertEquals(OnboardingWorkingDestination, onboardingDestinationFor(SessionState.Loading, null))
        assertEquals(OnboardingWorkingDestination, onboardingDestinationFor(SessionState.Loading, true))
        assertEquals(OnboardingWorkingDestination, onboardingDestinationFor(SessionState.NeedsBootstrap, true))
        // The stored flags are not read yet: wait, don't flash the welcome screen.
        assertEquals(OnboardingWorkingDestination, onboardingDestinationFor(SessionState.NeedsAge, null))
        assertEquals(OnboardingWelcomeDestination, onboardingDestinationFor(SessionState.NeedsAge, false))
        assertEquals(OnboardingAgeDestination, onboardingDestinationFor(SessionState.NeedsAge, true))
        assertEquals(OnboardingNoticeDestination, onboardingDestinationFor(SessionState.NeedsConsent, true))
        assertEquals(OnboardingSignInDestination, onboardingDestinationFor(SessionState.SignedOut, true))
        for (reason in BlockReason.entries) {
            assertEquals(
                OnboardingBlockedDestination,
                onboardingDestinationFor(SessionState.Blocked(reason), true),
            )
        }
        for (retryable in listOf(true, false)) {
            assertEquals(
                OnboardingProblemDestination,
                onboardingDestinationFor(SessionState.Error(retryable), true),
            )
        }
        assertNull(onboardingDestinationFor(SessionState.Ready, true))
    }

    @Test
    fun `process death - the stored flags alone decide where onboarding resumes`() {
        // A restarted process has only the flags and whatever the session works out from them.
        val seen = SessionFlags(welcomeSeen = true)
        assertEquals(OnboardingWelcomeDestination, onboardingDestinationFor(SessionState.NeedsAge, SessionFlags().welcomeSeen))
        assertEquals(OnboardingAgeDestination, onboardingDestinationFor(SessionState.NeedsAge, seen.welcomeSeen))
        // Killed on the SMS code screen: the user is signed out, so back to the phone screen.
        assertEquals(OnboardingSignInDestination, onboardingDestinationFor(SessionState.SignedOut, true))
    }

    // --- Settings: account -----------------------------------------------------------------

    @Test
    fun `settings loads the account when it opens`() = runTest {
        val session = FakeSession(SessionState.Ready)
        val viewModel = AccountViewModel(session)

        val loaded = viewModel.account.value as AccountUiState.Loaded
        assertEquals("+91 ••••• ••123", loaded.account.maskedPhone)
        assertEquals(listOf("account_core"), loaded.account.grantedPurposes)
        assertEquals(listOf("account"), session.calls)
    }

    @Test
    fun `an account that cannot be loaded is shown as unavailable, and try again loads it`() = runTest {
        val session = FakeSession(SessionState.Ready).apply {
            accountResult = AccountResult.Unavailable(retryable = true)
        }
        val viewModel = AccountViewModel(session)
        assertEquals(AccountUiState.Unavailable, viewModel.account.value)

        session.accountResult = AccountResult.Loaded(AccountSummary(null, "moderator", "bn", emptyList()))
        viewModel.load()

        val loaded = viewModel.account.value as AccountUiState.Loaded
        assertNull(loaded.account.maskedPhone)
        assertTrue(loaded.account.grantedPurposes.isEmpty())
    }

    @Test
    fun `sign out goes through the session`() = runTest {
        val session = FakeSession(SessionState.Ready)
        val viewModel = AccountViewModel(session)

        viewModel.signOut()

        assertEquals(listOf("account", "signOut"), session.calls)
        assertEquals(SessionState.NeedsAge, session.state.value)
        assertEquals(SessionFlags(), session.currentFlags)
    }
}
