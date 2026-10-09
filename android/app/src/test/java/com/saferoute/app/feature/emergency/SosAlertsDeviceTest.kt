// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.BuildConfig
import com.saferoute.app.R
import com.saferoute.app.core.data.SosContact
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.FakeActiveSosContacts
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SosAlertStatus
import com.saferoute.app.core.emergency.SosAlertSummary
import com.saferoute.app.core.emergency.SosLanguage
import com.saferoute.app.core.emergency.SosLocationReport
import com.saferoute.app.core.session.AppLocale
import com.saferoute.app.feature.contacts.ContactsError
import com.saferoute.app.feature.contacts.FakeContactsRepository
import com.saferoute.app.testing.assertMinTouchTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Numbers from the fake range. Nothing is sent or opened in a JVM test.
private val FAKE_PHONES = listOf("+919000010001", "+919000010002")
private const val FAKE_TEXT = "Test User needs help. Test message."

/** The phone's side of the alerts: the gate, the way of sending, the SMS app as the fallback. */
@RunWith(AndroidJUnit4::class)
class SosAlertsDeviceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private fun string(id: Int): String = application.getString(id)

    @Test
    fun `the gate is closed - until the notice version 2 exists no alert may be sent`() {
        assertFalse(runBlocking { ClosedSosAlertPolicy().alertsAllowed() })
    }

    @Test
    fun `the way of sending follows the build and the permission`() {
        val source = AndroidSmsModeSource(application)
        assertEquals("without the permission: the SMS app", SmsMode.COMPOSER, source.mode())

        assumeTrue(BuildConfig.SEND_SMS_DECLARED)
        shadowOf(application).grantPermissions(Manifest.permission.SEND_SMS)
        assertEquals(SmsMode.AUTOMATIC, source.mode())
    }

    @Test
    fun `the messages use the app's language and have no name yet`() {
        val english = AppSosMessageSettings(AppLocale { "en" })
        val bengali = AppSosMessageSettings(AppLocale { "bn" })

        assertEquals(SosLanguage.EN, runBlocking { english.language() })
        assertEquals(SosLanguage.BN, runBlocking { bengali.language() })
        assertNull(runBlocking { english.name() })
    }

    @Test
    fun `the composer intent goes to an SMS app with every number and the text, and sends nothing`() {
        val intent = composerIntent(FAKE_PHONES, FAKE_TEXT)

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto:+919000010001;+919000010002", intent.dataString)
        assertEquals(FAKE_TEXT, intent.getStringExtra("sms_body"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the composer notification shows fixed text only and carries the message unchangeable`() {
        val notification = buildSosComposerNotification(application, FAKE_PHONES, FAKE_TEXT)

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(string(R.string.sos_composer_title), title)
        assertEquals(string(R.string.sos_composer_text), text)
        // Nothing of a contact or of the message is on the notification itself.
        val visible = "$title $text ${notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}"
        assertFalse(visible.contains("9000010"))
        assertFalse(visible.contains("Test User"))

        val open = shadowOf(notification.contentIntent)
        assertTrue(open.isImmutable && open.isActivity)
        assertEquals(Intent.ACTION_SENDTO, open.savedIntent.action)
        assertEquals(FAKE_TEXT, open.savedIntent.getStringExtra("sms_body"))
        // The one button is Call 112: the dialer, never a call.
        val dial = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:112", dial.dataString)
    }

    @Test
    fun `showing posts the notification on a high-importance channel, and dismissing removes it`() {
        val composer = AndroidSmsComposer(application)

        composer.show(FAKE_PHONES, FAKE_TEXT)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(SOS_COMPOSER_CHANNEL_ID).importance)
        assertTrue(shadowOf(manager).getNotification(SOS_COMPOSER_NOTIFICATION_ID) != null)

        composer.dismiss()
        assertNull(shadowOf(manager).getNotification(SOS_COMPOSER_NOTIFICATION_ID))

        // With nobody to write to, nothing is shown.
        composer.show(emptyList(), FAKE_TEXT)
        assertNull(shadowOf(manager).getNotification(SOS_COMPOSER_NOTIFICATION_ID))
    }

    @Test
    fun `the fresh look returns who may still be alerted, or nothing when the list could not be fetched`() {
        val repository = FakeContactsRepository()
        val contacts = FakeActiveSosContacts(listOf(SosContact("id-1", "Test Contact 1", "+919000010001")))
        val freshener = RepositoryContactsFreshener(repository, contacts)

        assertEquals(setOf("id-1"), runBlocking { freshener.allowedContactIds() })
        assertEquals(listOf("refresh"), repository.calls.toList())

        repository.failWith = ContactsError.NoConnection
        assertNull("no network: nobody is taken off", runBlocking { freshener.allowedContactIds() })
    }
}

/** What the active screen says about the messages. Counts only. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SosAlertLinesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()
    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private var state by mutableStateOf(SosUi.Active(location = SosLocationReport.Precise, notificationsOff = false))

    private fun show(locked: Boolean = false) {
        compose.setContent {
            SafeRouteTheme {
                SosActiveScreen(
                    state = state,
                    locked = locked,
                    dialerMissing = false,
                    onSafe = { events += "safe" },
                    onSafeConfirm = { events += "confirm" },
                    onSafeDismiss = {},
                    onContinue = {},
                    onCall112 = {},
                    onOpenComposer = { events += "composer" },
                    onTellContactsChange = { state = state.copy(tellContacts = it) },
                )
            }
        }
    }

    @Test
    fun `with alerts not switched on it says that nobody is messaged`() {
        show()

        compose.onNodeWithText(string(R.string.sos_active_contacts_none)).assertExists()
        compose.onAllNodesWithText(string(R.string.sos_alerts_caveat)).assertCountEquals(0)
    }

    @Test
    fun `sent, waiting, failed and skipped are shown as counts, and a sent message is not called delivered`() {
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 2, waiting = 1, failed = 1, skipped = 1)))
        show()

        compose.onNodeWithText(string(R.string.sos_alerts_sent, 2, 5)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_waiting, 1)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_failed, 1)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_skipped, 1)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_caveat)).assertExists()
        compose.onAllNodesWithText(string(R.string.sos_active_contacts_none)).assertCountEquals(0)

        // Lines with nothing to report are left out.
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 0)))
        compose.onNodeWithText(string(R.string.sos_alerts_sent, 2, 2)).assertExists()
        compose.onAllNodesWithText(string(R.string.sos_alerts_waiting, 0)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.sos_alerts_failed, 0)).assertCountEquals(0)
    }

    @Test
    fun `on the lock screen the same counts are shown and nothing else about the contacts`() {
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 0)))
        show(locked = true)

        compose.onNodeWithText(string(R.string.sos_alerts_sent, 2, 2)).assertExists()
        // Every text on the screen is a fixed resource with numbers filled in: there is no
        // place where a name or a phone number could appear, locked or not.
        compose.onAllNodesWithText("Test Contact", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("+91", substring = true).assertCountEquals(0)
    }

    @Test
    fun `while preparing and with no contact it says so`() {
        state = state.copy(alerts = SosAlertStatus.Preparing)
        show()
        compose.onNodeWithText(string(R.string.sos_alerts_preparing)).assertExists()

        state = state.copy(alerts = SosAlertStatus.NoContacts)
        compose.onNodeWithText(string(R.string.sos_alerts_no_contacts)).assertExists()
    }

    @Test
    fun `in composer mode it says that nothing is sent until Send is pressed, and offers the SMS app again`() {
        state = state.copy(alerts = SosAlertStatus.Composer(contacts = 3))
        show()

        compose.onNodeWithText(string(R.string.sos_alerts_composer, 3)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_composer_open))
            .performScrollTo().assertIsDisplayed().assertMinTouchTarget().performClick()
        assertEquals(listOf("composer"), events)
    }

    @Test
    fun `the confirmation offers tell my contacts, ticked, only when someone was told`() {
        state = state.copy(confirmingSafe = true, canTellContacts = true)
        show()

        compose.onNodeWithTag(SosTellContactsTag).assertIsDisplayed().assertIsOn().assertMinTouchTarget().performClick()
        compose.onNodeWithTag(SosTellContactsTag).assertIsOff()
        assertFalse(state.tellContacts)

        state = state.copy(canTellContacts = false)
        compose.onNodeWithTag(SosTellContactsTag).assertDoesNotExist()
    }

    @Test
    fun `a practice run shows no alert counts`() {
        state = state.copy(
            practice = true,
            alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 0)),
        )
        show()

        compose.onAllNodesWithText(string(R.string.sos_alerts_sent, 2, 2)).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.sos_practice_banner)).assertIsDisplayed()
    }
}
