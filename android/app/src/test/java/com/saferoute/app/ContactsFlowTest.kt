// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.contacts.ContactStatus
import com.saferoute.app.feature.contacts.FAKE_INVITE_URL
import com.saferoute.app.feature.contacts.FakeContactsPreferences
import com.saferoute.app.feature.contacts.FakeContactsRepository
import com.saferoute.app.feature.contacts.fakeContact
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Emergency contacts from end to end in the real [MainActivity]: the card on Home, the notice,
 * the form, the invite handed to the SMS app, "did you send it", and the list afterwards. The
 * contacts are the fake repository (no server, no database); everything else is the real app.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class ContactsFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject
    lateinit var contacts: FakeContactsRepository

    @Inject
    lateinit var preferences: FakeContactsPreferences

    @Before
    fun inject() {
        hilt.inject()
        // Other tests keep the Home card away; this one is about it.
        preferences.clearSnooze()
        ShadowLog.clear()
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun tap(id: Int) = compose.onNodeWithText(string(id)).performScrollTo().performClick()

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** For a button in a dialog, which is not inside anything that scrolls. */
    private fun click(id: Int) = compose.onNodeWithText(string(id)).performClick()

    private fun shown(id: Int) = compose.onAllNodesWithText(string(id)).fetchSemanticsNodes().isNotEmpty()

    /** The sheet opens further when its handle is tapped; the card sits in its content. */
    private fun openSheet() {
        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description)).performClick()
        compose.waitForIdle()
    }

    private fun openContactsFromSettings() {
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        tap(R.string.settings_contacts_title)
        waitFor(string(R.string.contacts_intro, 5))
    }

    @Test
    fun `from the home card to an invited contact, with the sms app in between`() {
        openSheet()
        waitFor(string(R.string.contacts_card_title))
        // The SOS control is still there with the card on screen.
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
        // Nothing asked about consent yet.
        assertFalse(contacts.calls.contains("hasConsent"))

        compose.onNodeWithText(string(R.string.contacts_card_action)).performClick()
        waitFor(string(R.string.contacts_empty_title))
        tap(R.string.contacts_add)

        // First contact: the notice, then the form.
        waitFor(string(R.string.contacts_notice_title))
        assertFalse(contacts.consentGranted)
        tap(R.string.contacts_notice_agree)
        waitFor(string(R.string.contacts_add_from_phone))
        assertTrue(contacts.consentGranted)

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Test Contact 9")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("90000 10009")
        tap(R.string.contacts_add_save)

        // The invite: the app hands a text to the SMS app and sends nothing itself.
        waitFor(string(R.string.contacts_invite_body, "Test Contact 9"))
        tap(R.string.contacts_invite_open)
        waitFor(string(R.string.contacts_invite_sent_question))
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("smsto:+919000010009", started.dataString)
        assertTrue(started.getStringExtra("sms_body")!!.endsWith(FAKE_INVITE_URL))
        assertEquals(ContactStatus.NotInvited, contacts.current.single().status)

        tap(R.string.contacts_invite_sent_yes)

        // Back on the list, which replaced the form on the way.
        waitFor(string(R.string.contacts_status_invited))
        compose.onNodeWithText("Test Contact 9").assertIsDisplayed()
        assertEquals(ContactStatus.Invited, contacts.current.single().status)

        // Nothing of it reached the log.
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable ?: ""}" }
        for (secret in listOf("Test Contact 9", "9000010009", "FAKE-TOKEN", FAKE_INVITE_URL)) {
            assertFalse(secret, logged.contains(secret))
        }
    }

    @Test
    fun `with a contact on the phone home shows no card`() {
        contacts.set(fakeContact(1))
        openSheet()
        compose.waitForIdle()

        assertFalse(shown(R.string.contacts_card_title))
    }

    @Test
    fun `not now hides the card and is remembered as a point in time`() {
        openSheet()
        waitFor(string(R.string.contacts_card_title))

        compose.onNodeWithText(string(R.string.contacts_card_not_now)).performClick()
        compose.waitForIdle()

        assertFalse(shown(R.string.contacts_card_title))
        val threeDays = 3L * 24 * 60 * 60 * 1000
        val remaining = preferences.current - System.currentTimeMillis()
        assertTrue("snoozed for $remaining ms", remaining in (threeDays - 60_000)..threeDays)
        assertEquals(emptyList<String>(), contacts.calls.filter { it != "refresh" })
    }

    @Test
    fun `settings leads to the list, a contact opens, and an opted-out one cannot be invited`() {
        contacts.set(fakeContact(1), fakeContact(2, optedOut = true))

        openContactsFromSettings()
        compose.onNodeWithText("Test Contact 2").performClick()
        waitFor(string(R.string.contacts_detail_opted_out))
        assertFalse(shown(R.string.contacts_detail_invite))
        assertFalse(shown(R.string.contacts_detail_invite_again))

        // Removing it leads back to the list.
        tap(R.string.contacts_detail_remove)
        click(R.string.contacts_remove_confirm)
        waitFor(string(R.string.contacts_intro, 5))
        assertEquals(listOf(fakeContact(1).id), contacts.current.map { it.id })
    }

    @Test
    fun `offline the list opens read-only and adding explains itself`() {
        contacts.set(fakeContact(1))
        contacts.failWith = com.saferoute.app.feature.contacts.ContactsError.NoConnection

        openContactsFromSettings()

        compose.onNodeWithText("Test Contact 1").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_offline)).assertIsDisplayed()
        tap(R.string.contacts_add)
        waitFor(string(R.string.contacts_error_offline))
        assertEquals(1, contacts.current.size)
    }

    @Test
    fun `stop removes everything after one confirmation`() {
        contacts.consentGranted = true
        contacts.set(fakeContact(1), fakeContact(2))
        openContactsFromSettings()

        tap(R.string.contacts_stop_button)
        click(R.string.contacts_stop_confirm)

        waitFor(string(R.string.contacts_stop_done))
        assertEquals(0, contacts.current.size)
        assertFalse(contacts.consentGranted)
    }
}
