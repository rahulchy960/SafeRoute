// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.auth.PhoneAuthError
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.NOTICE_VERSION
import com.saferoute.app.testing.assertMinTouchTarget
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every onboarding screen on its own: content, behaviour, 200% font and touch targets. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class OnboardingScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bengali: Context = localizedContext(context, "bn")

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun text(id: Int, vararg args: Any) = compose.onNodeWithText(string(id, *args))

    @Composable
    private fun Themed(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme(content = content)
        }
    }

    /** Scrolls to the node (needed at 200% font), then checks it is visible and big enough. */
    private fun SemanticsNodeInteraction.reachableButton(): SemanticsNodeInteraction =
        performScrollTo().assertIsDisplayed().assertMinTouchTarget()

    private fun scrollNoticeToEnd() {
        compose.onNodeWithTag(NOTICE_SCROLL_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
    }

    // --- Welcome ------------------------------------------------------------------------------

    @Test
    fun `welcome says what the app is not, mentions 112 and continues`() {
        var continued = 0
        compose.setContent { Themed { WelcomeScreen(onContinue = { continued++ }) } }

        text(R.string.welcome_title).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service), substring = true).assertIsDisplayed()
        compose.onNodeWithText("112", substring = true).assertIsDisplayed()
        text(R.string.welcome_safety_context).assertIsDisplayed()

        text(R.string.onboarding_continue).reachableButton().performClick()
        assertEquals(1, continued)
    }

    // --- Age gate -----------------------------------------------------------------------------

    @Test
    fun `18 or older continues at once`() {
        var adult = 0
        var minor = 0
        compose.setContent {
            Themed { AgeGateScreen(onAdult = { adult++ }, onUnder18Confirmed = { minor++ }) }
        }

        text(R.string.age_adult).reachableButton().performClick()

        assertEquals(1, adult)
        assertEquals(0, minor)
    }

    @Test
    fun `under 18 asks for confirmation first, and going back records nothing`() {
        var adult = 0
        var minor = 0
        compose.setContent {
            Themed { AgeGateScreen(onAdult = { adult++ }, onUnder18Confirmed = { minor++ }) }
        }

        text(R.string.age_minor).reachableButton().performClick()
        text(R.string.age_minor_dialog_title).assertIsDisplayed()
        text(R.string.age_minor_dialog_body).assertIsDisplayed()
        assertEquals(0, minor)

        text(R.string.age_minor_dialog_cancel).performClick()
        text(R.string.age_minor_dialog_title).assertDoesNotExist()
        assertEquals(0, minor)
        assertEquals(0, adult)
    }

    @Test
    fun `under 18 is recorded only after the confirmation`() {
        var minor = 0
        compose.setContent { Themed { AgeGateScreen(onAdult = {}, onUnder18Confirmed = { minor++ }) } }

        text(R.string.age_minor).performClick()
        text(R.string.age_minor_dialog_confirm).assertMinTouchTarget().performClick()

        assertEquals(1, minor)
        text(R.string.age_minor_dialog_title).assertDoesNotExist()
    }

    // --- Blocked ------------------------------------------------------------------------------

    @Test
    fun `the under-18 screen is kind, shows the 112 note and a contact, and has no button`() {
        compose.setContent { Themed { BlockedScreen(reason = BlockReason.UNDER_18) } }

        text(R.string.blocked_minor_title).assertIsDisplayed()
        text(R.string.blocked_minor_body).assertIsDisplayed()
        text(R.string.onboarding_emergency_note).assertIsDisplayed()
        compose.onNodeWithText("[grievance contact]", substring = true).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertCountEquals(0)
    }

    @Test
    fun `the deleted-account screen explains and has no button`() {
        compose.setContent { Themed { BlockedScreen(reason = BlockReason.ACCOUNT_DELETED) } }

        text(R.string.blocked_deleted_title).assertIsDisplayed()
        text(R.string.blocked_deleted_body).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertCountEquals(0)
    }

    // --- Working and problem ------------------------------------------------------------------

    @Test
    fun `the progress screen says what is happening`() {
        compose.setContent { Themed { WorkingScreen(creatingAccount = true) } }
        text(R.string.onboarding_creating_account).assertIsDisplayed()
    }

    @Test
    fun `the problem screen explains offline differently and offers try again`() {
        var retries = 0
        compose.setContent { Themed { ProblemScreen(retryable = true, onRetry = { retries++ }) } }

        text(R.string.onboarding_problem_offline).assertIsDisplayed()
        text(R.string.onboarding_problem_generic).assertDoesNotExist()
        text(R.string.onboarding_try_again).reachableButton().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `the problem screen has a generic message for everything else`() {
        compose.setContent { Themed { ProblemScreen(retryable = false, onRetry = {}) } }
        text(R.string.onboarding_problem_generic).assertIsDisplayed()
    }

    // --- Consent notice -----------------------------------------------------------------------

    @Test
    fun `I agree is disabled until the notice has been scrolled to its end`() {
        var agreed: String? = null
        compose.setContent {
            Themed { ConsentNoticeScreen(showDraftMarker = false, onAgree = { agreed = it }) }
        }

        text(R.string.notice_title).assertIsDisplayed()
        text(R.string.notice_agree).assertIsNotEnabled()
        text(R.string.notice_scroll_hint).assertIsDisplayed()
        text(R.string.notice_agree).performClick()
        assertEquals(null, agreed)

        scrollNoticeToEnd()

        text(R.string.notice_scroll_hint).assertDoesNotExist()
        text(R.string.notice_agree).assertIsEnabled().assertMinTouchTarget().performClick()
        assertEquals("en", agreed)
    }

    @Test
    fun `the notice lists every section, the version, and both placeholders`() {
        compose.setContent { Themed { ConsentNoticeScreen(showDraftMarker = false, onAgree = {}) } }

        for (section in NOTICE_SECTIONS) {
            text(section.title).performScrollTo().assertIsDisplayed()
            text(section.body).performScrollTo().assertIsDisplayed()
        }
        text(R.string.notice_version, NOTICE_VERSION).performScrollTo().assertIsDisplayed()

        val all = NOTICE_SECTIONS.joinToString("\n") { string(it.body) }
        assertTrue(all.contains("[operator name]"))
        assertTrue(all.contains("[grievance contact]"))
        assertTrue(all.contains("112"))
        text(R.string.notice_draft_marker).assertDoesNotExist()
    }

    @Test
    fun `debug builds mark the notice as a draft`() {
        compose.setContent { Themed { ConsentNoticeScreen(showDraftMarker = true, onAgree = {}) } }
        text(R.string.notice_draft_marker).assertIsDisplayed()
    }

    @Test
    fun `the language switch changes this screen only, not the app's language`() {
        var agreed: String? = null
        val appLocaleBefore = context.resources.configuration.locales[0]
        val defaultBefore = Locale.getDefault()
        compose.setContent {
            Themed { ConsentNoticeScreen(showDraftMarker = false, onAgree = { agreed = it }) }
        }
        text(R.string.notice_language_english).assertIsSelected()

        compose.onNodeWithText(string(R.string.notice_language_bengali)).assertMinTouchTarget().performClick()

        compose.onNodeWithText(bengali.getString(R.string.notice_title)).assertIsDisplayed()
        compose.onNodeWithText(bengali.getString(R.string.notice_s1_title)).performScrollTo().assertIsDisplayed()
        text(R.string.notice_title).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.notice_language_bengali)).performScrollTo().assertIsSelected()
        // The app itself is still in English.
        assertEquals(appLocaleBefore, context.resources.configuration.locales[0])
        assertEquals(defaultBefore, Locale.getDefault())
        assertEquals("Settings", context.getString(R.string.settings_title))

        scrollNoticeToEnd()
        compose.onNodeWithText(bengali.getString(R.string.notice_agree)).performClick()
        assertEquals("bn", agreed)
    }

    @Test
    fun `switching back to English restores the English text`() {
        compose.setContent { Themed { ConsentNoticeScreen(showDraftMarker = false, onAgree = {}) } }

        compose.onNodeWithText(string(R.string.notice_language_bengali)).performClick()
        compose.onNodeWithText(string(R.string.notice_language_english)).performClick()

        text(R.string.notice_title).assertIsDisplayed()
    }

    @Test
    fun `English and Bengali notices have the same sections, all filled in`() {
        for (section in NOTICE_SECTIONS) {
            for (id in listOf(section.title, section.body)) {
                val english = string(id)
                val translated = bengali.getString(id)
                assertTrue(english.isNotBlank() && translated.isNotBlank())
                assertFalse("still English: $english", english == translated)
                // The same number of bullet points and placeholders in both languages.
                assertEquals(english.count { it == '•' }, translated.count { it == '•' })
                assertEquals(english.contains("[operator name]"), translated.contains("[operator name]"))
                assertEquals(
                    english.contains("[grievance contact]"),
                    translated.contains("[grievance contact]"),
                )
                assertEquals(english.contains("112"), translated.contains("112"))
            }
        }
        assertEquals(8, NOTICE_SECTIONS.size)
    }

    @Test
    fun `declining explains why and calls nothing`() {
        var agreed = 0
        compose.setContent { Themed { ConsentNoticeScreen(showDraftMarker = false, onAgree = { agreed++ }) } }

        text(R.string.notice_decline).assertMinTouchTarget().performClick()

        text(R.string.notice_declined).assertIsDisplayed()
        assertEquals(0, agreed)
        // Still possible to change one's mind.
        scrollNoticeToEnd()
        text(R.string.notice_agree).assertIsEnabled()
    }

    // --- Phone --------------------------------------------------------------------------------

    @Test
    fun `the phone screen has a fixed +91, a phone keyboard field with autofill, and the India note`() {
        val typed = mutableListOf<String>()
        var sent = 0
        compose.setContent {
            Themed {
                PhoneEntryScreen(state = SignInUiState(), onPhoneChange = { typed += it }, onSendCode = { sent++ })
            }
        }

        text(R.string.phone_title).assertIsDisplayed()
        text(R.string.phone_country_code).assertIsDisplayed()
        text(R.string.phone_india_only).assertIsDisplayed()
        val field = compose.onNode(hasSetTextAction())
        field.assertIsDisplayed().assertMinTouchTarget()
        field.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.PhoneNumberNational),
        )
        field.performTextInput("9000000001")
        assertEquals(listOf("9000000001"), typed)

        text(R.string.phone_send_code).reachableButton().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun `an invalid number is said in words and announced as an error`() {
        compose.setContent {
            Themed {
                PhoneEntryScreen(
                    state = SignInUiState(phoneInput = "12345", phoneInvalid = true),
                    onPhoneChange = {},
                    onSendCode = {},
                )
            }
        }

        text(R.string.phone_error_invalid).assertIsDisplayed()
        val error = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Error)
        assertEquals(string(R.string.phone_error_invalid), error)
    }

    @Test
    fun `while the code is being sent the button is off and the screen says so`() {
        compose.setContent {
            Themed { PhoneEntryScreen(state = SignInUiState(busy = true), onPhoneChange = {}, onSendCode = {}) }
        }

        text(R.string.phone_send_code).assertIsNotEnabled()
        text(R.string.phone_sending).assertIsDisplayed()
    }

    // --- Code ---------------------------------------------------------------------------------

    @Composable
    private fun Code(state: SignInUiState, events: MutableList<String> = mutableListOf()) = Themed {
        CodeEntryScreen(
            state = state,
            onCodeChange = { events += "code:$it" },
            onVerify = { events += "verify" },
            onResend = { events += "resend" },
            onChangeNumber = { events += "change" },
        )
    }

    @Test
    fun `the code screen has a number field with the SMS autofill hint`() {
        val events = mutableListOf<String>()
        compose.setContent { Code(SignInUiState(step = SignInStep.CODE, resendInSeconds = 42), events) }

        val field = compose.onNode(hasSetTextAction())
        field.assertIsDisplayed().assertMinTouchTarget()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.SmsOtpCode))
        field.performTextInput("123456")
        assertEquals(listOf("code:123456"), events)

        // Six digits are not there yet in this state: Verify is off.
        text(R.string.code_verify).assertIsNotEnabled()
        // The wait is written out, and the resend button is off until it is over.
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.code_resend_wait, 42, 42),
        ).performScrollTo().assertIsDisplayed()
        text(R.string.code_resend).assertIsNotEnabled()
    }

    @Test
    fun `with six digits and the wait over, every action works`() {
        val events = mutableListOf<String>()
        compose.setContent { Code(SignInUiState(step = SignInStep.CODE, codeInput = "123456"), events) }

        text(R.string.code_verify).reachableButton().performClick()
        text(R.string.code_resend).reachableButton().performClick()
        text(R.string.code_change_number).reachableButton().performClick()

        assertEquals(listOf("verify", "resend", "change"), events)
    }

    @Test
    fun `each sign-in failure has its own sentence, shown and announced`() {
        val seen = mutableSetOf<String>()
        for (error in PhoneAuthError.entries) seen += string(signInErrorText(error))
        assertEquals(PhoneAuthError.entries.size, seen.size)

        compose.setContent {
            Code(SignInUiState(step = SignInStep.CODE, error = PhoneAuthError.WRONG_CODE))
        }
        text(R.string.signin_error_wrong_code).assertIsDisplayed()
        val announced = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Error)
        assertEquals(string(R.string.signin_error_wrong_code), announced)
    }

    @Test
    fun `no error sentence repeats a number or a code`() {
        for (error in PhoneAuthError.entries) {
            for (ctx in listOf(context, bengali)) {
                val sentence = ctx.getString(signInErrorText(error))
                assertFalse(sentence, sentence.contains(Regex("""\d{4,}""")))
                assertFalse(sentence, sentence.contains("%"))
            }
        }
    }

    // --- 200% font ----------------------------------------------------------------------------

    @Test
    fun `at 200 percent font every onboarding screen can be scrolled to its buttons`() {
        val screens: List<Pair<@Composable () -> Unit, List<Int>>> = listOf(
            Pair({ WelcomeScreen(onContinue = {}) }, listOf(R.string.onboarding_continue)),
            Pair(
                { AgeGateScreen(onAdult = {}, onUnder18Confirmed = {}) },
                listOf(R.string.age_adult, R.string.age_minor),
            ),
            Pair({ ProblemScreen(retryable = true, onRetry = {}) }, listOf(R.string.onboarding_try_again)),
            Pair(
                { PhoneEntryScreen(state = SignInUiState(), onPhoneChange = {}, onSendCode = {}) },
                listOf(R.string.phone_send_code),
            ),
            Pair(
                {
                    CodeEntryScreen(
                        state = SignInUiState(step = SignInStep.CODE, codeInput = "123456"),
                        onCodeChange = {},
                        onVerify = {},
                        onResend = {},
                        onChangeNumber = {},
                    )
                },
                listOf(R.string.code_verify, R.string.code_resend, R.string.code_change_number),
            ),
        )
        var index by mutableIntStateOf(0)
        compose.setContent { Themed(fontScale = 2f) { screens[index].first() } }

        for (i in screens.indices) {
            index = i
            compose.waitForIdle()
            for (button in screens[i].second) text(button).reachableButton()
        }
    }

    @Test
    fun `at 200 percent font the notice still scrolls to its end and both buttons are reachable`() {
        compose.setContent {
            Themed(fontScale = 2f) { ConsentNoticeScreen(showDraftMarker = true, onAgree = {}) }
        }

        text(R.string.notice_agree).assertIsDisplayed().assertIsNotEnabled()
        scrollNoticeToEnd()
        text(R.string.notice_agree).assertIsDisplayed().assertIsEnabled().assertMinTouchTarget()
        text(R.string.notice_decline).assertIsDisplayed().assertMinTouchTarget()
    }

    @Test
    fun `at 200 percent font the blocked screen shows all of its text`() {
        compose.setContent { Themed(fontScale = 2f) { BlockedScreen(reason = BlockReason.UNDER_18) } }

        text(R.string.blocked_minor_body).performScrollTo().assertIsDisplayed()
        text(R.string.blocked_minor_mistake).performScrollTo().assertIsDisplayed()
    }
}
