// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.auth.FAKE_SMS_CODE
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.auth.FirebaseIdTokenProvider
import com.saferoute.app.core.auth.PhoneAuthGateway
import com.saferoute.app.core.auth.di.AuthModule
import com.saferoute.app.core.network.auth.IdTokenProvider
import com.saferoute.app.core.session.FakeMeApi
import com.saferoute.app.core.session.FakeMeApi.Answer
import com.saferoute.app.core.session.GET_CONSENTS
import com.saferoute.app.core.session.GET_ME
import com.saferoute.app.core.session.InMemorySessionStore
import com.saferoute.app.core.session.LOCALE_ENGLISH
import com.saferoute.app.core.session.ONBOARDED
import com.saferoute.app.core.session.POST_BOOTSTRAP
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionRepository
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The first sign-in in the real [MainActivity] with the REAL [SessionRepository], the real
 * navigation and the real sign-in ViewModel; only Firebase and the API are fakes (P009d).
 *
 * `OnboardingNavigationTest` uses a fake session and therefore could not see the bug this test
 * is for: navigation replaced the sign-in screen while that screen's ViewModel was running the
 * session check, the check was cancelled after `GET /v1/me`, and the app stayed on
 * "One moment...". Here the answers take real time (a few milliseconds each), so the screen is
 * really replaced in the middle of the check.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class, AuthModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class SignInToHomeTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val fakeGateway = FakePhoneAuthGateway()
    private val api = FakeMeApi().apply {
        getMe = listOf(Answer.Problem(403, "bootstrap_required"), Answer.Ok())
        latencyMillis = 30
    }

    // Age and consent are done; the phone number is not verified yet.
    private val repository = SessionRepository(
        fakeGateway,
        InMemorySessionStore(ONBOARDED),
        api,
        { LOCALE_ENGLISH },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @BindValue
    @JvmField
    val session: Session = repository

    @BindValue
    @JvmField
    val gateway: PhoneAuthGateway = fakeGateway

    @BindValue
    @JvmField
    val tokens: IdTokenProvider = FirebaseIdTokenProvider(fakeGateway)

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun waitForText(id: Int) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(string(id)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun signIn() {
        waitForText(R.string.phone_title)
        compose.onNode(hasSetTextAction()).performTextInput("9000000001")
        compose.onNodeWithText(string(R.string.phone_send_code)).performScrollTo().performClick()
        waitForText(R.string.code_title)
        compose.onNode(hasSetTextAction()).performTextInput(FAKE_SMS_CODE)
        compose.onNodeWithText(string(R.string.code_verify)).performScrollTo().performClick()
    }

    @Test
    fun `after the SMS code a new user gets an account and reaches Home`() {
        signIn()

        waitForText(R.string.home_sheet_title)

        compose.onNodeWithText(string(R.string.emergency_button_label)).assertIsDisplayed()
        assertEquals(listOf(GET_ME, POST_BOOTSTRAP, GET_ME, GET_CONSENTS), api.calls)
        assertEquals(1, api.bootstrapBodies.size)
        assertEquals(SessionState.Ready, repository.state.value)
    }

    @Test
    fun `when the account cannot be created the retry screen shows, and Try again reaches Home`() {
        api.bootstrap = listOf(Answer.NoConnection, Answer.Ok(201))
        api.getMe = listOf(
            Answer.Problem(403, "bootstrap_required"),
            Answer.Problem(403, "bootstrap_required"),
            Answer.Ok(),
        )

        signIn()

        // Not an endless spinner: the problem screen with its button.
        waitForText(R.string.onboarding_problem_offline)
        compose.onNodeWithText(string(R.string.onboarding_try_again)).performScrollTo().performClick()

        waitForText(R.string.home_sheet_title)
        assertEquals(SessionState.Ready, repository.state.value)
    }
}
