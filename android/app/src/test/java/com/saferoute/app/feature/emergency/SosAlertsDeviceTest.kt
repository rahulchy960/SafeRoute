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
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.saferoute.app.core.emergency.AlertLanguageChoice
import com.saferoute.app.core.emergency.FakeSosSettingsStore
import com.saferoute.app.core.emergency.SosLanguage
import com.saferoute.app.core.emergency.SosLocationReport
import com.saferoute.app.core.session.AppLocale
import com.saferoute.app.feature.contacts.ContactsError
import com.saferoute.app.feature.contacts.FakeContactsPreferences
import com.saferoute.app.feature.contacts.FakeContactsRepository
import com.saferoute.app.feature.contacts.SOS_ALERTS_NOTICE_VERSION
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
    fun `the gate opens only for consent to the notice the app shows today`() {
        val preferences = FakeContactsPreferences()
        val policy = ConsentSosAlertPolicy(preferences)

        assertFalse("nothing agreed", runBlocking { policy.alertsAllowed() })

        runBlocking { preferences.setAlertsNoticeVersion("2026-10-alerts-draft1") }
        assertFalse("the older notice did not describe the alerts", runBlocking { policy.alertsAllowed() })

        // Version 2 did not say that an SOS can be started from outside the app (P014c1).
        runBlocking { preferences.setAlertsNoticeVersion("2026-10-alerts-draft2") }
        assertFalse("version 2 is asked again", runBlocking { policy.alertsAllowed() })

        // Version 3 did not say that the alert can carry a name the user entered (P014c2b).
        runBlocking { preferences.setAlertsNoticeVersion("2026-10-alerts-draft3") }
        assertFalse("version 3 is asked again", runBlocking { policy.alertsAllowed() })

        runBlocking { preferences.setAlertsNoticeVersion(SOS_ALERTS_NOTICE_VERSION) }
        assertTrue(runBlocking { policy.alertsAllowed() })

        // Withdrawn or signed out.
        runBlocking { preferences.setAlertsNoticeVersion(null) }
        assertFalse(runBlocking { policy.alertsAllowed() })
    }

    @Test
    fun `the notice version is the fourth one, and differs from the first three`() {
        assertEquals("2026-10-alerts-draft4", SOS_ALERTS_NOTICE_VERSION)
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
    fun `without a choice the messages use the app's language and have no name`() {
        val english = AppSosMessageSettings(AppLocale { "en" }, FakeSosSettingsStore())
        val bengali = AppSosMessageSettings(AppLocale { "bn" }, FakeSosSettingsStore())

        assertEquals(SosLanguage.EN, runBlocking { english.language() })
        assertEquals(SosLanguage.BN, runBlocking { bengali.language() })
        assertNull(runBlocking { english.name() })
    }

    @Test
    fun `the messages carry the name the user set and the language the user chose`() {
        val store = FakeSosSettingsStore()
        val settings = AppSosMessageSettings(AppLocale { "en" }, store)

        runBlocking { store.setName("  Test   User \n") }
        assertEquals("one line, trimmed", "Test User", runBlocking { settings.name() })

        // The alert's language need not be the app's: a contact may read Bengali only.
        runBlocking { store.setLanguage(AlertLanguageChoice.BN) }
        assertEquals(SosLanguage.BN, runBlocking { settings.language() })
        runBlocking { store.setLanguage(AlertLanguageChoice.EN) }
        assertEquals(SosLanguage.EN, runBlocking { AppSosMessageSettings(AppLocale { "bn" }, store).language() })
        runBlocking { store.setLanguage(AlertLanguageChoice.APP) }
        assertEquals(SosLanguage.BN, runBlocking { AppSosMessageSettings(AppLocale { "bn" }, store).language() })

        // Read at the moment the alert is written: a removed name is gone from the next one.
        runBlocking { store.setName(null) }
        assertNull(runBlocking { settings.name() })
    }

    @Test
    fun `the countdown tone needs no permission and never stops a countdown`() {
        // Robolectric has no sound hardware: whatever the phone answers, play() returns.
        val tone = AndroidCountdownTone()
        repeat(3) { tone.play() }

        val source = java.io.File("src/main/java/com/saferoute/app/feature/emergency/SosDevice.kt").readText()
        assertTrue("the alarm volume, not the ringer", source.contains("AudioManager.STREAM_ALARM"))
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        for (permission in listOf("MODIFY_AUDIO_SETTINGS", "ACCESS_NOTIFICATION_POLICY", "RECORD_AUDIO")) {
            assertFalse(permission, manifest.contains(permission))
        }
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
                    onRetryNow = { events += "retry" },
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
    fun `messages held back by a build's list of test numbers have their own line and are not called opted out`() {
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 1, waiting = 0, failed = 0, skipped = 0, held = 2)))
        show()

        compose.onNodeWithText(string(R.string.sos_alerts_sent, 1, 3)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_held, 2)).assertExists()
        compose.onAllNodesWithText(string(R.string.sos_alerts_skipped, 2)).assertCountEquals(0)

        // No build limit, no line.
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 1, waiting = 0, failed = 0, skipped = 0)))
        compose.onAllNodesWithText(string(R.string.sos_alerts_held, 0)).assertCountEquals(0)
    }

    @Test
    fun `while an alert is unsent the screen says that it keeps trying and offers send again now`() {
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 1, waiting = 1, failed = 0, skipped = 0)))
        show()

        compose.onNodeWithText(string(R.string.sos_alerts_retry_explain)).assertExists()
        compose.onNodeWithTag(SosRetryNowTag).performScrollTo().assertIsDisplayed().assertMinTouchTarget()
        compose.onNodeWithText(string(R.string.sos_alerts_retry_now)).performClick()
        assertEquals(listOf("retry"), events)
        // Not said before it is true.
        compose.onAllNodesWithText(string(R.string.sos_alerts_unsent_long)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.sos_alerts_stopped)).assertCountEquals(0)

        // Everything sent: nothing to send again.
        state = state.copy(alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 0)))
        compose.onAllNodesWithTag(SosRetryNowTag).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.sos_alerts_retry_explain)).assertCountEquals(0)
    }

    @Test
    fun `after two hours the screen says that alerts are unsent and what to do, with the button still there`() {
        state = state.copy(
            alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 0, waiting = 2, failed = 0, skipped = 0), unsentForLong = true),
        )
        show()

        val sentence = string(R.string.sos_alerts_unsent_long)
        assertEquals("Some alerts have not been sent. Call 112 or send a text yourself.", sentence)
        compose.onNodeWithText(sentence).assertExists()
        compose.onNodeWithTag(SosRetryNowTag).assertExists()
    }

    @Test
    fun `after 24 hours the screen says that trying has stopped, and offers no send again`() {
        state = state.copy(
            alerts = SosAlertStatus.Automatic(SosAlertSummary(sent = 1, waiting = 0, failed = 1, skipped = 0), retryingStopped = true),
        )
        show()

        compose.onNodeWithText(string(R.string.sos_alerts_stopped)).assertExists()
        compose.onNodeWithText(string(R.string.sos_alerts_failed, 1)).assertExists()
        compose.onAllNodesWithTag(SosRetryNowTag).assertCountEquals(0)
        assertTrue(string(R.string.sos_alerts_stopped).contains("112"))
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
