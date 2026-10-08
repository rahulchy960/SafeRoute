// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.auth.FAKE_ID_TOKEN
import com.saferoute.app.core.auth.FAKE_SMS_CODE
import com.saferoute.app.core.auth.FakePhoneAuthGateway
import com.saferoute.app.core.auth.FirebaseIdTokenProvider
import com.saferoute.app.core.auth.PhoneAuthGateway
import com.saferoute.app.core.auth.di.AuthModule
import com.saferoute.app.core.network.auth.IdTokenProvider
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.NOTICE_VERSION
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionFlags
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.onboarding.NOTICE_SCROLL_TAG
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Onboarding in the real [MainActivity], from a fresh install to Home, with the session and
 * sign-in replaced by fakes. No Firebase, no server, no SMS: the phone number typed here is a
 * made-up pattern and is never used to send anything.
 *
 * It proves the order (welcome → age → consent → phone → code → Home), that Home cannot be
 * reached earlier, and that the under-18 path touches neither sign-in nor the network.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class, AuthModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class OnboardingNavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val fakeSession = FakeSession(SessionState.NeedsAge)
    private val fakeGateway = FakePhoneAuthGateway()

    @BindValue
    @JvmField
    val session: Session = fakeSession

    @BindValue
    @JvmField
    val gateway: PhoneAuthGateway = fakeGateway

    @BindValue
    @JvmField
    val tokens: IdTokenProvider = FirebaseIdTokenProvider(fakeGateway)

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun text(id: Int) = compose.onNodeWithText(string(id))

    /** Home's emergency control: found by what TalkBack reads, it has no visible sentence. */
    private fun sosControl() =
        compose.onNodeWithContentDescription(string(R.string.sos_control_description))

    private fun assertHomeIsNotShown() {
        sosControl().assertDoesNotExist()
        text(R.string.home_sheet_title).assertDoesNotExist()
        text(R.string.search_hint).assertDoesNotExist()
    }

    private fun scrollNoticeToEnd() {
        compose.onNodeWithTag(NOTICE_SCROLL_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `a fresh install goes welcome, age, consent, phone, code, and only then Home`() {
        // Welcome
        text(R.string.welcome_title).assertIsDisplayed()
        assertHomeIsNotShown()
        text(R.string.onboarding_continue).performScrollTo().performClick()

        // Age gate
        text(R.string.age_title).assertIsDisplayed()
        assertHomeIsNotShown()
        text(R.string.age_adult).performScrollTo().performClick()

        // Consent notice: nothing has been asked of sign-in yet.
        text(R.string.notice_title).assertIsDisplayed()
        assertHomeIsNotShown()
        assertTrue(fakeGateway.calls.isEmpty())
        scrollNoticeToEnd()
        text(R.string.notice_agree).performClick()

        // Phone
        text(R.string.phone_title).assertIsDisplayed()
        assertHomeIsNotShown()
        assertEquals(NOTICE_VERSION, fakeSession.currentFlags.acceptedNoticeVersion)
        compose.onNode(hasSetTextAction()).performTextInput("9000000001")
        text(R.string.phone_send_code).performScrollTo().performClick()

        // Code
        text(R.string.code_title).assertIsDisplayed()
        assertHomeIsNotShown()
        fakeSession.afterRefresh = SessionState.Ready
        compose.onNode(hasSetTextAction()).performTextInput(FAKE_SMS_CODE)
        text(R.string.code_verify).performScrollTo().performClick()

        // Home
        sosControl().assertIsDisplayed()
        text(R.string.home_sheet_title).assertIsDisplayed()
        assertEquals(
            listOf("refresh", "markWelcomeSeen", "confirmAdult", "acceptNotice(en)", "onSignedIn"),
            fakeSession.calls,
        )
        assertEquals(listOf("startVerification", "verifyCode"), fakeGateway.calls)

        // Nothing typed on the way reached Android's log.
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg}" }
        assertFalse(logged.contains("9000000001"))
        assertFalse(logged.contains(FAKE_ID_TOKEN))
    }

    @Test
    fun `under 18 - after the confirmation the app is blocked with no sign-in and no network`() {
        text(R.string.onboarding_continue).performScrollTo().performClick()
        text(R.string.age_minor).performScrollTo().performClick()

        // The dialog: nothing is recorded yet.
        text(R.string.age_minor_dialog_title).assertIsDisplayed()
        assertEquals(SessionState.NeedsAge, fakeSession.state.value)
        text(R.string.age_minor_dialog_confirm).performClick()

        text(R.string.blocked_minor_title).assertIsDisplayed()
        text(R.string.onboarding_emergency_note).assertIsDisplayed()
        compose.onNodeWithText("[grievance contact]", substring = true).assertIsDisplayed()
        assertHomeIsNotShown()
        assertEquals(SessionState.Blocked(BlockReason.UNDER_18), fakeSession.state.value)
        assertTrue(fakeGateway.calls.isEmpty())
        assertTrue(fakeGateway.tokenRequests.isEmpty())
        assertEquals(listOf("refresh", "markWelcomeSeen", "declareUnder18"), fakeSession.calls)

        // Back leaves the app; it does not return to the age question.
        pressSystemBack()
        assertEquals(true, compose.activity.isFinishing)
    }

    @Test
    fun `declining the notice stays on the notice and tells the session nothing`() {
        fakeSession.setState(SessionState.NeedsConsent)
        compose.waitForIdle()

        text(R.string.notice_decline).performClick()

        text(R.string.notice_declined).assertIsDisplayed()
        text(R.string.notice_title).assertIsDisplayed()
        assertEquals(listOf("refresh"), fakeSession.calls)
        assertTrue(fakeGateway.calls.isEmpty())
        assertHomeIsNotShown()
    }

    @Test
    fun `back on the code screen returns to the phone screen, not out of the app`() {
        fakeSession.setState(SessionState.SignedOut)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("9000000001")
        text(R.string.phone_send_code).performScrollTo().performClick()
        text(R.string.code_title).assertIsDisplayed()

        pressSystemBack()

        text(R.string.phone_title).assertIsDisplayed()
        assertEquals(false, compose.activity.isFinishing)
    }

    @Test
    fun `a wrong code shows its message and stays on the code screen`() {
        fakeSession.setState(SessionState.SignedOut)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("9000000001")
        text(R.string.phone_send_code).performScrollTo().performClick()

        compose.onNode(hasSetTextAction()).performTextInput("111111")
        text(R.string.code_verify).performScrollTo().performClick()

        text(R.string.signin_error_wrong_code).performScrollTo().assertIsDisplayed()
        assertHomeIsNotShown()
    }

    @Test
    fun `each session state shows its screen, and Home only for Ready`() {
        fakeSession.setState(SessionState.Loading)
        compose.waitForIdle()
        text(R.string.onboarding_working).assertIsDisplayed()

        fakeSession.setState(SessionState.NeedsBootstrap)
        compose.waitForIdle()
        text(R.string.onboarding_creating_account).assertIsDisplayed()
        assertHomeIsNotShown()

        fakeSession.setState(SessionState.Error(retryable = true))
        compose.waitForIdle()
        text(R.string.onboarding_problem_offline).assertIsDisplayed()
        assertHomeIsNotShown()

        // "Try again" asks the session again; this time it works.
        fakeSession.afterRefresh = SessionState.Ready
        text(R.string.onboarding_try_again).performScrollTo().performClick()
        sosControl().assertIsDisplayed()

        fakeSession.setState(SessionState.Blocked(BlockReason.ACCOUNT_DELETED))
        compose.waitForIdle()
        text(R.string.blocked_deleted_title).assertIsDisplayed()
        assertHomeIsNotShown()
    }

    @Test
    fun `sign out from Settings asks first, then returns to the start of onboarding`() {
        fakeSession.setState(SessionState.Ready)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()

        compose.onNodeWithText("+91 ••••• ••123", useUnmergedTree = true).assertIsDisplayed()
        text(R.string.settings_sign_out).performScrollTo().performClick()
        text(R.string.settings_sign_out_dialog_title).assertIsDisplayed()
        assertEquals(SessionState.Ready, fakeSession.state.value)

        // The dialog's confirm button has the same label as the row's button.
        compose.onAllNodes(hasText(string(R.string.settings_sign_out)))[1]
            .performClick()
        compose.waitForIdle()

        assertTrue("signOut" in fakeSession.calls)
        assertEquals(SessionFlags(), fakeSession.currentFlags)
        text(R.string.welcome_title).assertIsDisplayed()
        text(R.string.settings_about_title).assertDoesNotExist()
        assertHomeIsNotShown()
    }
}
