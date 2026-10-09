// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The contacts screens on their own, without navigation or a ViewModel. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class ContactsScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Composable
    private fun Themed(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme(content = content)
        }
    }

    private fun showList(state: ContactsUiState, fontScale: Float = 1f) = compose.setContent {
        Themed(fontScale) {
            ContactsScreen(
                state = state,
                onBack = { events += "back" },
                onAdd = { events += "add" },
                onOpenContact = { events += "open:$it" },
                onRefresh = { events += "refresh" },
                onStopConfirmed = { events += "stop" },
            )
        }
    }

    private fun showAdd(state: AddContactUiState, fontScale: Float = 1f) = compose.setContent {
        Themed(fontScale) {
            AddContactScreen(
                state = state,
                onBack = { events += "back" },
                onRetryCheck = { events += "retry" },
                onAgree = { events += "agree" },
                onPickFromPhone = { events += "pick" },
                onNameChange = { events += "name" },
                onPhoneChange = { events += "phone" },
                onSave = { events += "save" },
            )
        }
    }

    private fun showInvite(state: InviteUiState, fontScale: Float = 1f) = compose.setContent {
        Themed(fontScale) {
            InviteScreen(
                state = state,
                onWrite = { events += "write" },
                onSent = { events += "sent" },
                onLater = { events += "later" },
            )
        }
    }

    private fun showDetail(state: ContactDetailUiState, renameAccepted: Boolean = true) = compose.setContent {
        Themed {
            ContactDetailScreen(
                state = state,
                onBack = { events += "back" },
                onInvite = { events += "invite" },
                onRename = {
                    events += "rename:$it"
                    renameAccepted
                },
                onRemoveConfirmed = { events += "remove" },
            )
        }
    }

    // The list ---------------------------------------------------------------------------

    @Test
    fun `the list shows each contact with its status in words`() {
        showList(
            ContactsUiState(
                contacts = listOf(fakeContact(1), fakeContact(2, invited = true), fakeContact(3, optedOut = true)),
            ),
        )

        compose.onNodeWithText("Test Contact 1").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_status_not_invited)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_status_invited)).assertIsDisplayed()
        compose.onNodeWithText("Opted out — won't be alerted").assertIsDisplayed()

        compose.onNodeWithText("Test Contact 2").performClick()
        assertEquals(listOf("open:${fakeContact(2).id}"), events)
    }

    @Test
    fun `an empty list says so and offers add`() {
        showList(ContactsUiState(contacts = emptyList()))

        compose.onNodeWithText(string(R.string.contacts_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_add)).assertIsEnabled().assertMinTouchTarget().performClick()
        assertEquals(listOf("add"), events)
        // The limit is not mentioned while there is room.
        compose.onAllNodesWithText(string(R.string.contacts_limit_reason, 5)).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `while the copy is still being read the list does not claim to be empty`() {
        showList(ContactsUiState(contacts = null))

        assertTrue(compose.onAllNodesWithText(string(R.string.contacts_empty_title)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `at five contacts add is disabled and the reason is given in words`() {
        showList(ContactsUiState(contacts = (1..5).map { fakeContact(it) }))

        compose.onNodeWithText(string(R.string.contacts_add)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.contacts_limit_reason, 5)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `offline the list is shown and says that changes need the internet`() {
        showList(ContactsUiState(contacts = listOf(fakeContact(1)), offline = true))

        compose.onNodeWithText("Test Contact 1").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_offline)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.contacts_refresh)).assertMinTouchTarget().performClick()
        assertEquals(listOf("refresh"), events)
    }

    @Test
    fun `stop asks first, cancel does nothing, confirm reports it once`() {
        showList(ContactsUiState(contacts = listOf(fakeContact(1))))
        val button = string(R.string.contacts_stop_button)

        compose.onNodeWithText(button).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.contacts_stop_dialog_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_cancel)).performClick()
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithText(button).performClick()
        compose.onNodeWithText(string(R.string.contacts_stop_confirm)).performClick()
        assertEquals(listOf("stop"), events)
    }

    @Test
    fun `after everything was removed the screen confirms it`() {
        showList(ContactsUiState(contacts = emptyList(), stopped = true))

        compose.onNodeWithText(string(R.string.contacts_stop_done)).assertIsDisplayed()
    }

    @Test
    fun `at 200 percent font every button of the list can be reached`() {
        showList(ContactsUiState(contacts = (1..5).map { fakeContact(it, optedOut = it == 5) }), fontScale = 2f)

        compose.onNodeWithText("Test Contact 5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_add)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_stop_button)).performScrollTo().assertIsDisplayed()
            .assertMinTouchTarget()
    }

    // The notice -------------------------------------------------------------------------

    @Test
    fun `the notice shows every paragraph, the draft marker and two plain buttons`() {
        showAdd(AddContactUiState.Notice())

        compose.onNodeWithText(string(R.string.contacts_notice_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.notice_draft_marker)).assertIsDisplayed()
        for (paragraph in ALERTS_NOTICE_PARAGRAPHS) {
            compose.onNodeWithText(string(paragraph)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(string(R.string.notice_version, SOS_ALERTS_NOTICE_VERSION)).performScrollTo()
            .assertIsDisplayed()

        // Nothing to tick, so nothing can be pre-ticked: agreeing is one deliberate tap.
        compose.onNodeWithText(string(R.string.contacts_notice_agree)).performScrollTo().assertMinTouchTarget()
            .performClick()
        compose.onNodeWithText(string(R.string.contacts_notice_not_now)).performScrollTo().assertMinTouchTarget()
            .performClick()
        assertEquals(listOf("agree", "back"), events)
    }

    @Test
    fun `the notice says what an SOS sends, that it may cost, and that it is not an emergency service`() {
        val text = ALERTS_NOTICE_PARAGRAPHS.joinToString(" ") { string(it) }

        // Version 2: the alerts are real, so the notice describes them.
        assertTrue(text.contains("sends each of them an SMS from your own SIM"))
        assertTrue(text.contains("a map link to where your phone is"))
        assertTrue(text.contains("Your mobile plan may charge you"))
        assertTrue(text.contains("Nothing is sent unless you start an SOS"))
        assertTrue(text.contains("opens your SMS app with the message and you press Send"))
        assertTrue(text.contains("stays on this phone for up to 30 days"))
        assertTrue(text.contains("Someone who opted out is never alerted"))
        assertTrue(text.contains("SafeRoute is not an emergency service."))
        assertTrue(text.contains("In an emergency, call 112."))
        assertFalse(text.contains("later version"))
        // It promises no delivery and no safety. ("if you say you are safe" is the user's own
        // statement, the only place the word appears.)
        assertTrue(text.contains("A message can arrive late or not at all"))
        assertFalse(text.contains("guarantee", ignoreCase = true))
        assertEquals(1, Regex("""\bsafe\b""", RegexOption.IGNORE_CASE).findAll(text).count())
        assertTrue(text.contains("if you say you are safe"))
    }

    @Test
    fun `at 200 percent font both buttons of the notice can be reached`() {
        showAdd(AddContactUiState.Notice(), fontScale = 2f)

        compose.onNodeWithText(string(R.string.contacts_notice_agree)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_notice_not_now)).performScrollTo().assertIsDisplayed()
    }

    // The form ---------------------------------------------------------------------------

    @Test
    fun `the form offers the phone's contacts and typing, and reports each`() {
        showAdd(AddContactUiState.Form())

        compose.onNodeWithText(string(R.string.contacts_add_from_phone)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.contacts_add_from_phone_help)).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Test Contact 9")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("9000010009")
        compose.onNodeWithText(string(R.string.contacts_add_save)).performScrollTo().performClick()

        // Replacing a text can report more than one change; the order is what matters.
        assertEquals(listOf("pick", "name", "phone", "save"), events.distinct())
    }

    @Test
    fun `the form marks what is wrong and shows the server's answer calmly`() {
        showAdd(
            AddContactUiState.Form(
                name = "",
                phone = "033",
                nameInvalid = true,
                phoneInvalid = true,
                error = ContactsError.OptedOut,
                pickProblem = PickProblem.Unreadable,
            ),
        )

        compose.onNodeWithText(string(R.string.contacts_add_name_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_add_phone_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_error_opted_out)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_pick_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `while saving the form cannot be sent twice`() {
        showAdd(AddContactUiState.Form(name = "Test Contact 9", phone = "9000010009", saving = true))

        compose.onNodeWithText(string(R.string.contacts_add_saving)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.contacts_add_from_phone)).assertIsNotEnabled()
    }

    @Test
    fun `a failed check explains itself and offers try again`() {
        showAdd(AddContactUiState.CheckFailed(ContactsError.NoConnection))

        compose.onNodeWithText(string(R.string.contacts_error_offline)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_try_again)).performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun `every error has its own calm sentence`() {
        val errors = listOf(
            ContactsError.NoConnection, ContactsError.Unavailable, ContactsError.RateLimited(5),
            ContactsError.RateLimited(null), ContactsError.AlreadyExists, ContactsError.OptedOut,
            ContactsError.OwnNumber, ContactsError.Invalid, ContactsError.LimitReached, ContactsError.NotFound,
        )
        val sentences = errors.map { string(it.message(), 5) }

        assertEquals(errors.size, sentences.toSet().size)
        for (sentence in sentences) {
            assertFalse(sentence, Regex("""\b(error|failed|fatal|409|403)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sentence))
        }
    }

    // The invite -------------------------------------------------------------------------

    @Test
    fun `the invite step says who sends the message`() {
        showInvite(InviteUiState(contactName = "Test Contact 1"))

        compose.onNodeWithText(string(R.string.contacts_invite_body, "Test Contact 1")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_invite_open)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.contacts_invite_later)).performClick()
        assertEquals(listOf("write", "later"), events)
        assertTrue(string(R.string.contacts_invite_body, "x").contains("SafeRoute sends nothing"))
    }

    @Test
    fun `back in the app the question is did you send it, with yes and later`() {
        showInvite(InviteUiState(contactName = "Test Contact 1", step = InviteStep.AskSent))

        compose.onNodeWithText(string(R.string.contacts_invite_sent_question)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_invite_sent_yes)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.contacts_invite_later)).performClick()
        assertEquals(listOf("sent", "later"), events)
    }

    @Test
    fun `while the link is prepared the button is off, and without an sms app the screen says so`() {
        showInvite(InviteUiState(contactName = "Test Contact 1", step = InviteStep.Preparing))
        compose.onNodeWithText(string(R.string.contacts_invite_open)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.contacts_invite_preparing)).assertIsDisplayed()
    }

    @Test
    fun `a phone without an sms app gets an explanation and a way out`() {
        showInvite(InviteUiState(contactName = "Test Contact 1", step = InviteStep.NoSmsApp))

        compose.onNodeWithText(string(R.string.contacts_invite_no_sms_app)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_done)).performClick()
        assertEquals(listOf("later"), events)
    }

    @Test
    fun `at 200 percent font the invite buttons can be reached`() {
        showInvite(InviteUiState(contactName = "Test Contact 1", step = InviteStep.AskSent), fontScale = 2f)

        compose.onNodeWithText(string(R.string.contacts_invite_sent_yes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_invite_later)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the invite text is honest and has room for the link`() {
        for (locale in listOf("en", "bn")) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(locale))
            val text = context.createConfigurationContext(config)
                .getString(R.string.contacts_invite_message, FAKE_INVITE_URL)

            assertTrue(locale, text.endsWith(FAKE_INVITE_URL))
            assertTrue(locale, text.contains("SafeRoute"))
        }
        // "may", not "will": no promise that a message arrives.
        assertTrue(string(R.string.contacts_invite_message, "x").contains("my phone may text you my location"))
    }

    // One contact ------------------------------------------------------------------------

    @Test
    fun `a contact can be invited, renamed and removed, each after its own step`() {
        showDetail(ContactDetailUiState(loading = false, contact = fakeContact(1)))

        compose.onNodeWithText(string(R.string.contacts_detail_invite)).assertMinTouchTarget().performClick()

        compose.onNodeWithText(string(R.string.contacts_detail_rename)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("New Name")
        compose.onNodeWithText(string(R.string.contacts_rename_save)).performClick()

        compose.onNodeWithText(string(R.string.contacts_detail_remove)).performClick()
        compose.onNodeWithText(string(R.string.contacts_remove_dialog_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_remove_confirm)).performClick()

        assertEquals(listOf("invite", "rename:New Name", "remove"), events)
    }

    @Test
    fun `a name that is refused keeps the rename field open with the reason`() {
        showDetail(ContactDetailUiState(loading = false, contact = fakeContact(1)), renameAccepted = false)

        compose.onNodeWithText(string(R.string.contacts_detail_rename)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText(string(R.string.contacts_rename_save)).performClick()

        // Still open, with the rule in view, and the attempt was reported once.
        compose.onNodeWithText(string(R.string.contacts_rename_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_add_name_error)).assertIsDisplayed()
        assertEquals(listOf("rename:"), events)
    }

    @Test
    fun `an invited contact offers send again`() {
        showDetail(ContactDetailUiState(loading = false, contact = fakeContact(1, invited = true)))

        compose.onNodeWithText(string(R.string.contacts_detail_invite_again)).assertIsDisplayed()
    }

    @Test
    fun `an opted-out contact has no invite button, and the screen explains why`() {
        showDetail(ContactDetailUiState(loading = false, contact = fakeContact(1, optedOut = true)))

        compose.onNodeWithText(string(R.string.contacts_detail_opted_out)).assertIsDisplayed()
        for (label in listOf(R.string.contacts_detail_invite, R.string.contacts_detail_invite_again)) {
            assertTrue(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().none { node ->
                node.config.toString().contains(string(label))
            })
        }
        // Removing stays possible.
        compose.onNodeWithText(string(R.string.contacts_detail_remove)).assertIsEnabled()
    }

    // The card ---------------------------------------------------------------------------

    @Test
    fun `the home card has a title, an action and not now`() {
        compose.setContent {
            Themed(fontScale = 2f) { ContactsCard(onAdd = { events += "add" }, onNotNow = { events += "notNow" }) }
        }

        compose.onNodeWithText(string(R.string.contacts_card_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_card_action)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.contacts_card_not_now)).assertMinTouchTarget().performClick()
        assertEquals(listOf("add", "notNow"), events)
    }
}
