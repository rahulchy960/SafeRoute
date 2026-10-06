// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.auth.FAKE_SMS_CODE
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.network.generated.model.BootstrapConsent
import com.saferoute.app.core.network.generated.model.BootstrapMeRequest
import com.saferoute.app.core.session.FakeMeApi.Answer
import com.saferoute.app.feature.onboarding.SignInViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The live path right after the SMS code, with the REAL [SessionRepository] and the REAL
 * [SignInViewModel] together (P009d).
 *
 * The bug this guards against: the sign-in screen's ViewModel started the session check, the
 * check's first step changed the state to "loading", navigation replaced the sign-in screen,
 * Android cleared that ViewModel, and the check was cancelled half-way. The account was never
 * created and the app showed "One moment..." for ever. A cold start worked, because there the
 * check is started by the activity's ViewModel.
 *
 * [ScreenHost] plays the part of navigation: it clears the sign-in ViewModel as soon as the
 * session leaves `SignedOut`, exactly as `OnboardingNavHost` does.
 */
@OptIn(ExperimentalCoroutinesApi::class) // setMain and the virtual-time helpers, test code only
@RunWith(AndroidJUnit4::class)
class PostSignInFlowTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val gateway = FakePhoneAuthGateway()
    private val api = FakeMeApi().apply {
        // A new user: no account yet, then it exists.
        getMe = listOf(Answer.Problem(403, "bootstrap_required"), Answer.Ok())
        // Every call takes a moment, as on a real network.
        latencyMillis = 50
    }
    private val store = InMemorySessionStore(ONBOARDED.copy(acceptedNoticeLocale = LOCALE_BENGALI))
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).get()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newRepository() = newSessionRepository(gateway, store, api, this)

    /** Holds the sign-in ViewModel like a navigation back-stack entry does. */
    private class ScreenHost(gateway: FakePhoneAuthGateway, session: Session) {
        private val viewModelStore = ViewModelStore()
        val signIn: SignInViewModel = ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SignInViewModel(gateway, session) as T
            },
        )[SignInViewModel::class.java]

        /** The screen left the back stack: Android clears its ViewModel and cancels its work. */
        fun leave() = viewModelStore.clear()
    }

    private fun TestScope.typePhoneAndCode(host: ScreenHost) {
        host.signIn.onPhoneChange("90000 00001")
        host.signIn.submitPhone(activity)
        runCurrent()
        host.signIn.onCodeChange(FAKE_SMS_CODE)
        host.signIn.submitCode()
    }

    @Test
    fun `first sign-in - the account is created although the sign-in screen is cleared mid-flow`() = scope.runTest {
        val repository = newRepository()
        repository.refresh()
        assertEquals(SessionState.SignedOut, repository.state.value)
        val host = ScreenHost(gateway, repository)

        // Navigation: when the session leaves SignedOut, the sign-in screen is replaced.
        backgroundScope.launch {
            repository.state.collect { if (it != SessionState.SignedOut) host.leave() }
        }

        typePhoneAndCode(host)
        advanceUntilIdle()

        assertEquals(
            listOf(GET_ME, POST_BOOTSTRAP, GET_ME, GET_CONSENTS),
            api.calls,
        )
        assertEquals(
            listOf(
                BootstrapMeRequest(
                    locale = BootstrapMeRequest.Locale.en,
                    consent = BootstrapConsent(
                        noticeVersion = NOTICE_VERSION,
                        noticeLocale = BootstrapConsent.NoticeLocale.bn,
                        purposes = listOf(PURPOSE_ACCOUNT_CORE),
                        ageConfirmed = true,
                    ),
                ),
            ),
            api.bootstrapBodies,
        )
        assertEquals(SessionState.Ready, repository.state.value)
        assertEquals(true, store.current.readyOnce)
    }

    private val firstSignInCalls = listOf(GET_ME, POST_BOOTSTRAP, GET_ME, GET_CONSENTS)

    /** Signed out and onboarded, with "navigation" that clears the sign-in screen. */
    private fun TestScope.signedOutApp(): Pair<SessionRepository, ScreenHost> {
        val repository = newRepository()
        val host = ScreenHost(gateway, repository)
        backgroundScope.launch {
            repository.refresh()
            repository.state.collect { if (it != SessionState.SignedOut) host.leave() }
        }
        runCurrent()
        assertEquals(SessionState.SignedOut, repository.state.value)
        return repository to host
    }

    // --- When Firebase reports the sign-in -------------------------------------------------

    @Test
    fun `auth state arriving AFTER verifyCode still creates the account`() = scope.runTest {
        gateway.signInCompletesLater = true
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceUntilIdle()
        // The code was accepted, but Firebase has no user yet: nothing was sent, and the
        // sign-in screen is usable, not stuck on "checking".
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(SessionState.SignedOut, repository.state.value)
        assertEquals(false, host.signIn.state.value.busy)

        gateway.completeSignIn()
        advanceUntilIdle()

        assertEquals(firstSignInCalls, api.calls)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `auth state arriving DURING verifyCode starts one check, not two`() = scope.runTest {
        // The default fake: the user appears, and the auth state is emitted, before verifyCode
        // returns. Both the listener and the sign-in screen then report the sign-in.
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceUntilIdle()

        assertEquals(firstSignInCalls, api.calls)
        assertEquals(1, api.bootstrapBodies.size)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `a second emission of the same auth state changes nothing`() = scope.runTest {
        val (repository, host) = signedOutApp()
        typePhoneAndCode(host)
        // While the check is running...
        advanceTimeBy(60)
        gateway.reportAuthStateAgain()
        gateway.reportAuthStateAgain()
        advanceUntilIdle()
        // ...and after it has finished.
        gateway.reportAuthStateAgain()
        advanceUntilIdle()

        assertEquals(firstSignInCalls, api.calls)
        assertEquals(1, api.bootstrapBodies.size)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `sign-in without typing a code (Android read the SMS) creates the account too`() = scope.runTest {
        val (repository, _) = signedOutApp()

        // Firebase signs the user in on its own; no screen calls anything.
        gateway.signInDirectly()
        advanceUntilIdle()

        assertEquals(firstSignInCalls, api.calls)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    // --- The loading state always ends ------------------------------------------------------

    @Test
    fun `bootstrap 503 shows the retry screen, and retrying finishes the sign-in`() = scope.runTest {
        api.bootstrap = listOf(Answer.Problem(503, "db_unavailable"), Answer.Ok(201))
        api.getMe = listOf(
            Answer.Problem(403, "bootstrap_required"),
            Answer.Problem(403, "bootstrap_required"),
            Answer.Ok(),
        )
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceUntilIdle()
        assertEquals(SessionState.Error(retryable = true), repository.state.value)
        assertEquals(false, store.current.readyOnce)

        repository.refresh()
        assertEquals(SessionState.Ready, repository.state.value)
        assertEquals(2, api.bootstrapBodies.size)
    }

    @Test
    fun `a bootstrap that never answers ends in the retry screen within the time limit`() = scope.runTest {
        api.bootstrap = listOf(Answer.Hangs)
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceTimeBy(SESSION_CHECK_TIMEOUT_MILLIS - 1_000)
        runCurrent()
        // Still within the limit: account creation is in progress.
        assertEquals(SessionState.NeedsBootstrap, repository.state.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(SessionState.Error(retryable = true), repository.state.value)

        // The next attempt is not blocked by the one that hung.
        api.bootstrap = listOf(Answer.Ok(201))
        api.getMe = listOf(Answer.Problem(403, "bootstrap_required"), Answer.Ok())
        repository.refresh()
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `a first request that never answers ends in the retry screen too`() = scope.runTest {
        api.getMe = listOf(Answer.Hangs)
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceTimeBy(SESSION_CHECK_TIMEOUT_MILLIS + 1_000)
        runCurrent()

        assertEquals(SessionState.Error(retryable = true), repository.state.value)
        assertEquals(listOf(GET_ME), api.calls)
    }

    @Test
    fun `no connection during the loading step shows the retry screen`() = scope.runTest {
        api.bootstrap = listOf(Answer.NoConnection)
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceUntilIdle()

        assertEquals(SessionState.Error(retryable = true), repository.state.value)
    }

    @Test
    fun `consents failing after the bootstrap shows retry, and the retry does not bootstrap again`() = scope.runTest {
        api.getConsents = listOf(Answer.Problem(503, "db_unavailable"), Answer.Ok())
        val (repository, host) = signedOutApp()

        typePhoneAndCode(host)
        advanceUntilIdle()
        assertEquals(SessionState.Error(retryable = true), repository.state.value)

        repository.refresh()

        assertEquals(SessionState.Ready, repository.state.value)
        assertEquals(1, api.bootstrapBodies.size)
    }

    @Test
    fun `an unexpected failure inside the check ends in an error, never a spinner`() = scope.runTest {
        val failingStore = object : SessionStore by store {
            override suspend fun update(transform: (SessionFlags) -> SessionFlags) {
                throw IllegalStateException("fake: the flags could not be written")
            }
        }
        val repository = newSessionRepository(gateway, failingStore, api, this)
        gateway.signInDirectly()

        repository.refresh()

        // Everything was sent, but "ready once" could not be saved.
        assertEquals(SessionState.Error(retryable = false), repository.state.value)
    }

    // --- Rules that must not change ---------------------------------------------------------

    @Test
    fun `cold start for a new user is unchanged - same calls, same result`() = scope.runTest {
        gateway.signInDirectly()
        val repository = newRepository()

        // What the activity's ViewModel does when the app starts.
        repository.refresh()

        assertEquals(firstSignInCalls, api.calls)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `cold start for an existing user is unchanged`() = scope.runTest {
        api.getMe = listOf(Answer.Ok())
        gateway.signInDirectly()
        val repository = newRepository()

        repository.refresh()

        assertEquals(listOf(GET_ME, GET_CONSENTS), api.calls)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `offline-open rule - a check that hangs keeps Home open for a user who was ready before`() = scope.runTest {
        api.getMe = listOf(Answer.Hangs)
        store.update { it.copy(readyOnce = true) }
        gateway.signInDirectly()
        val repository = newRepository()

        backgroundScope.launch { repository.refresh() }
        runCurrent()
        // Home at once, before any answer.
        assertEquals(SessionState.Ready, repository.state.value)

        advanceTimeBy(SESSION_CHECK_TIMEOUT_MILLIS + 1_000)
        runCurrent()
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `under-18 rule - a sign-in report never gets past the block or sends anything`() = scope.runTest {
        store.update { SessionFlags(under18 = true) }
        val repository = newRepository()
        repository.refresh()
        assertEquals(SessionState.Blocked(BlockReason.UNDER_18), repository.state.value)

        gateway.signInDirectly()
        gateway.reportAuthStateAgain()
        repository.onSignedIn()
        advanceUntilIdle()

        assertEquals(SessionState.Blocked(BlockReason.UNDER_18), repository.state.value)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `consent rule - a signed-in user without the accepted notice is asked first, nothing is created`() = scope.runTest {
        store.update { SessionFlags(ageConfirmed = true) }
        gateway.signInDirectly()
        val repository = newRepository()

        repository.refresh()

        assertEquals(SessionState.NeedsConsent, repository.state.value)
        assertEquals(listOf(GET_ME), api.calls)
        assertEquals(0, api.bootstrapBodies.size)
    }

    @Test
    fun `the work survives its caller - a cancelled caller does not cancel the check`() = scope.runTest {
        gateway.signInDirectly()
        val repository = newRepository()

        val caller = launch { repository.refresh() }
        advanceTimeBy(60)
        caller.cancel()
        advanceUntilIdle()

        assertEquals(firstSignInCalls, api.calls)
        assertEquals(SessionState.Ready, repository.state.value)
    }
}
