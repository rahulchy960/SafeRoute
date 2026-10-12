// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.data.SosContact
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.AlertLanguageChoice
import com.saferoute.app.core.emergency.ContactsFreshness
import com.saferoute.app.core.emergency.FakeSosSettingsStore
import com.saferoute.app.core.emergency.SosSettings
import com.saferoute.app.core.emergency.FakeActiveSosContacts
import com.saferoute.app.core.emergency.FakeSmsModeSource
import com.saferoute.app.core.emergency.FakeSosAlertPolicy
import com.saferoute.app.core.emergency.ReadinessFacts
import com.saferoute.app.core.emergency.ReadinessItem
import com.saferoute.app.core.emergency.ReadinessLocation
import com.saferoute.app.core.emergency.ReadinessStatus
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.sosReadiness
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.session.AppLocale
import com.saferoute.app.feature.contacts.ContactsError
import com.saferoute.app.feature.contacts.FakeContactsPreferences
import com.saferoute.app.feature.contacts.FakeContactsRepository
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element

private val NOW: Instant = Instant.parse("2026-10-13T12:00:00Z")

/** What the screen reads from the phone, each time it comes to the front (P014c2a). */
@OptIn(ExperimentalCoroutinesApi::class)
class SosSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val policy = FakeSosAlertPolicy(open = true)
    private val contacts = FakeActiveSosContacts(listOf(SosContact("id-1", "Test Contact 1", "+919000010001")))
    private val preferences = FakeContactsPreferences()
    private val repository = FakeContactsRepository()
    private val smsMode = FakeSmsModeSource(SmsMode.AUTOMATIC)
    private val environment = FakeLocationEnvironment(granted = GrantedLocation.Precise)
    private val gate = FakeNotificationGate()
    private var language = "en"
    private val reader = SosReadinessReader(
        policy, contacts, preferences, smsMode, environment, gate, Clock.fixed(NOW, ZoneOffset.UTC),
    ).also { it.smsDeclared = true }
    private val alertSettings = FakeSosSettingsStore()

    @Test
    fun `the name, the language and the sound are saved on the phone and shown at once`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals("nothing chosen: no name, the app's language, no sound", SosSettings(), viewModel.state.value!!.settings)

        viewModel.onNameSave("  Test  User ")
        viewModel.onLanguageChange(AlertLanguageChoice.BN)
        viewModel.onCountdownSoundChange(true)
        advanceUntilIdle()

        val shown = viewModel.state.value!!.settings
        assertEquals(SosSettings("Test User", AlertLanguageChoice.BN, countdownSound = true), shown)
        assertEquals(shown, alertSettings.value)
        // None of the three is a thing to fix.
        assertEquals(0, viewModel.state.value!!.readiness.toFix)
        // Saving a name asks no server.
        assertEquals(emptyList<String>(), repository.calls.toList())

        viewModel.onNameSave("   ")
        advanceUntilIdle()
        assertNull("a blank name removes it", viewModel.state.value!!.settings.name)
        assertFalse(viewModel.state.value!!.settings.toString().contains("Test"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SosSetupViewModel {
        Dispatchers.setMain(dispatcher)
        runBlocking { preferences.setContactsSyncedAt(NOW.minus(Duration.ofHours(2)).toEpochMilli()) }
        return SosSetupViewModel(reader, repository, AppLocale { language }, alertSettings)
    }

    @Test
    fun `it shows nothing until the screen is in front, then everything in order is ready`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertNull(viewModel.state.value)

        viewModel.onResume()
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertTrue(state.readiness.ready)
        assertEquals(1, state.activeContacts)
        assertEquals(ContactsFreshness.Today, state.readiness.freshness)
        assertEquals(ReadinessLocation.PRECISE, state.location)
        assertFalse(state.bengali)
        // Looking is not fetching: the server is asked only by "Refresh".
        assertEquals(emptyList<String>(), repository.calls.toList())
    }

    @Test
    fun `every return to the screen reads the phone again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value!!.readiness.toFix)

        // The user went to the phone's settings and changed things, then came back.
        environment.granted = GrantedLocation.Approximate
        gate.block = NotificationBlock.PermissionMissing
        policy.open = false
        contacts.list = emptyList()
        smsMode.mode = SmsMode.COMPOSER
        language = "bn"
        assertEquals("nothing is watched in the background", 0, viewModel.state.value!!.readiness.toFix)

        viewModel.onResume()
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals(5, state.readiness.toFix)
        assertEquals(ReadinessLocation.APPROXIMATE, state.location)
        assertEquals(0, state.activeContacts)
        assertTrue(state.bengali)
    }

    @Test
    fun `location is read as not allowed, switched off, approximate or precise`() = runTest(dispatcher) {
        val viewModel = viewModel()
        suspend fun read(): ReadinessLocation {
            viewModel.onResume()
            advanceUntilIdle()
            return viewModel.state.value!!.location
        }

        environment.granted = GrantedLocation.None
        assertEquals(ReadinessLocation.NOT_ALLOWED, read())
        environment.granted = GrantedLocation.Precise
        environment.locationEnabled = false
        assertEquals(ReadinessLocation.SWITCHED_OFF, read())
        environment.locationEnabled = true
        environment.granted = GrantedLocation.Approximate
        assertEquals(ReadinessLocation.APPROXIMATE, read())
        environment.granted = GrantedLocation.Precise
        assertEquals(ReadinessLocation.PRECISE, read())
    }

    @Test
    fun `the SMS row has two states for the SMS app - to fix, or how this build works`() = runTest(dispatcher) {
        val viewModel = viewModel()
        smsMode.mode = SmsMode.COMPOSER
        suspend fun sms(): ReadinessStatus {
            viewModel.onResume()
            advanceUntilIdle()
            return viewModel.state.value!!.readiness.status(ReadinessItem.SMS)
        }

        reader.smsDeclared = true
        assertEquals(ReadinessStatus.NEEDS_ACTION, sms())
        reader.smsDeclared = false
        assertEquals(ReadinessStatus.INFO, sms())
        smsMode.mode = SmsMode.AUTOMATIC
        reader.smsDeclared = true
        assertEquals(ReadinessStatus.OK, sms())
    }

    @Test
    fun `one notification category switched off is not notifications off`() = runTest(dispatcher) {
        val viewModel = viewModel()
        suspend fun status(block: NotificationBlock): ReadinessStatus {
            gate.block = block
            viewModel.onResume()
            advanceUntilIdle()
            return viewModel.state.value!!.readiness.status(ReadinessItem.NOTIFICATIONS)
        }

        assertEquals(ReadinessStatus.OK, status(NotificationBlock.None))
        assertEquals(ReadinessStatus.OK, status(NotificationBlock.ChannelBlocked))
        assertEquals(ReadinessStatus.NEEDS_ACTION, status(NotificationBlock.PermissionMissing))
        assertEquals(ReadinessStatus.NEEDS_ACTION, status(NotificationBlock.AppBlocked))
    }

    @Test
    fun `refresh fetches the list once and reads the phone again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runBlocking { preferences.setContactsSyncedAt(0) }
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(ContactsFreshness.Never, viewModel.state.value!!.readiness.freshness)

        viewModel.onRefresh()
        assertTrue(viewModel.state.value!!.refreshing)
        // A second tap while it runs does nothing.
        viewModel.onRefresh()
        // What the real repository does when the list arrived.
        runBlocking { preferences.setContactsSyncedAt(NOW.toEpochMilli()) }
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals(listOf("refresh"), repository.calls.toList())
        assertFalse(state.refreshing)
        assertFalse(state.refreshFailed)
        assertEquals(ContactsFreshness.Today, state.readiness.freshness)
    }

    @Test
    fun `a refresh without a connection says so and changes nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        repository.failWith = ContactsError.NoConnection
        viewModel.onResume()
        advanceUntilIdle()
        val before = viewModel.state.value!!

        viewModel.onRefresh()
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertTrue(state.refreshFailed)
        assertFalse(state.refreshing)
        assertEquals(before.readiness, state.readiness)
        assertEquals(before.activeContacts, state.activeContacts)

        // Coming back to the screen clears the message; it was about that one try.
        viewModel.onResume()
        advanceUntilIdle()
        assertFalse(viewModel.state.value!!.refreshFailed)
    }
}

/** The screen as a picture of a state, and the ways out of it. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SosSetupScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val allGood = ReadinessFacts(
        alertsSetUp = true,
        activeContacts = 2,
        contactsSyncedAt = NOW.minus(Duration.ofDays(12)),
        smsAutomatic = true,
        smsCanBeAutomatic = true,
        location = ReadinessLocation.PRECISE,
        notificationsAllowed = true,
    )

    private fun state(
        facts: ReadinessFacts,
        bengali: Boolean = false,
        refreshFailed: Boolean = false,
        settings: SosSettings = SosSettings(),
    ) = SosSetupUiState(
        readiness = sosReadiness(facts, NOW),
        activeContacts = facts.activeContacts,
        location = facts.location,
        bengali = bengali,
        settings = settings,
        refreshFailed = refreshFailed,
    )

    private val actions = SosSetupActions(
        onBack = { events += "back" },
        onRefresh = { events += "refresh" },
        onReadNotice = { events += "notice" },
        onOpenAppSettings = { events += "app" },
        onOpenNotificationSettings = { events += "notifications" },
        onOpenLocationSettings = { events += "location" },
        onPractice = { events += "practice" },
        onNameSave = { events += "name:" + (it ?: "removed") },
        onLanguageChange = { events += "language:" + it },
        onCountdownSoundChange = { events += "sound:" + it },
    )

    // The content is set once per test; a second show() only changes the state it draws.
    private val shown = mutableStateOf<SosSetupUiState?>(null)
    private var contentSet = false

    private fun show(state: SosSetupUiState?, fontScale: Float = 1f) {
        shown.value = state
        if (contentSet) {
            compose.waitForIdle()
            return
        }
        contentSet = true
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SafeRouteTheme { SosSetupScreen(state = shown.value, actions = actions, smsCard = {}) }
            }
        }
    }

    private fun row(item: ReadinessItem) = compose.onNodeWithTag(SosSetupRowTag + item.name, useUnmergedTree = true)

    private fun rowHas(item: ReadinessItem, text: String) {
        compose.onNode(
            androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(SosSetupRowTag + item.name)) and
                hasText(text),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `with everything in order it says Ready, each row says OK, and the battery says it cannot be checked`() {
        show(state(allGood))

        compose.onNodeWithTag(SosSetupSummaryTag).assertTextContains(string(R.string.sos_setup_ready))
        compose.onNodeWithText(string(R.string.sos_setup_caveat)).assertExists()
        for (item in ReadinessItem.entries - ReadinessItem.BATTERY) {
            row(item).assertExists()
            rowHas(item, string(R.string.sos_setup_status_ok))
        }
        rowHas(ReadinessItem.BATTERY, string(R.string.sos_setup_status_unknown))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_contacts_many, 2))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_updated_days, 12))
        // Nothing to fix: no way to the notice, to the notification or location settings.
        compose.onAllNodesWithText(string(R.string.contacts_alerts_read_notice)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.sos_setup_open_notification_settings)).assertCountEquals(0)
    }

    @Test
    fun `not ready counts what is to fix, and each such row leads to where it can be fixed`() {
        show(
            state(
                allGood.copy(
                    alertsSetUp = false,
                    location = ReadinessLocation.APPROXIMATE,
                    notificationsAllowed = false,
                ),
            ),
        )

        compose.onNodeWithTag(SosSetupSummaryTag).assertTextContains(string(R.string.sos_setup_not_ready_many, 3))
        rowHas(ReadinessItem.ALERTS, string(R.string.sos_setup_status_fix))
        rowHas(ReadinessItem.LOCATION, string(R.string.sos_setup_location_approximate))
        rowHas(ReadinessItem.NOTIFICATIONS, string(R.string.sos_setup_notifications_fix))

        compose.onNodeWithText(string(R.string.contacts_alerts_read_notice)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.sos_setup_open_notification_settings)).performScrollTo().performClick()
        // Two rows offer the app settings: location and battery. The first is location.
        compose.onAllNodesWithText(string(R.string.sos_setup_open_app_settings))[0].performScrollTo().performClick()
        assertEquals(listOf("notice", "notifications", "app"), events)
    }

    @Test
    fun `one thing to fix is said in the singular`() {
        show(state(allGood.copy(notificationsAllowed = false)))

        compose.onNodeWithTag(SosSetupSummaryTag).assertTextContains(string(R.string.sos_setup_not_ready_one))
    }

    @Test
    fun `the contacts row says none, cannot tell, or how many, and how old the list is`() {
        show(state(allGood.copy(activeContacts = 0, contactsSyncedAt = null)))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_status_unknown))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_contacts_unknown))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_updated_never))

        show(state(allGood.copy(activeContacts = 0, contactsSyncedAt = NOW)))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_status_fix))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_contacts_none))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_updated_today))

        show(state(allGood.copy(activeContacts = 1, contactsSyncedAt = NOW.minus(Duration.ofHours(30)))))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_contacts_one))
        rowHas(ReadinessItem.CONTACTS, string(R.string.sos_setup_updated_yesterday))
    }

    @Test
    fun `refresh is a button, and a refresh that failed says that the copy is unchanged`() {
        show(state(allGood, refreshFailed = true))

        compose.onNodeWithText(string(R.string.sos_setup_refresh_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_refresh)).performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf("refresh"), events)
    }

    @Test
    fun `the SMS row says automatic, your SMS app to fix, or your SMS app as how this build works`() {
        show(state(allGood))
        rowHas(ReadinessItem.SMS, string(R.string.sos_setup_sms_automatic))

        show(state(allGood.copy(smsAutomatic = false)))
        rowHas(ReadinessItem.SMS, string(R.string.sos_setup_status_fix))
        rowHas(ReadinessItem.SMS, string(R.string.sos_setup_sms_app))

        show(state(allGood.copy(smsAutomatic = false, smsCanBeAutomatic = false)))
        rowHas(ReadinessItem.SMS, string(R.string.sos_setup_status_info))
        rowHas(ReadinessItem.SMS, string(R.string.sos_setup_sms_app_only))
        compose.onNodeWithTag(SosSetupSummaryTag).assertTextContains(string(R.string.sos_setup_ready))
    }

    @Test
    fun `location switched off leads to the phone's location settings, not allowed to the app settings`() {
        show(state(allGood.copy(location = ReadinessLocation.SWITCHED_OFF)))
        rowHas(ReadinessItem.LOCATION, string(R.string.sos_setup_location_off))
        compose.onNodeWithText(string(R.string.sos_setup_open_location_settings)).performScrollTo().performClick()

        show(state(allGood.copy(location = ReadinessLocation.NOT_ALLOWED)))
        rowHas(ReadinessItem.LOCATION, string(R.string.sos_setup_location_not_allowed))
        rowHas(ReadinessItem.LOCATION, string(R.string.sos_setup_location_how))
        assertEquals(listOf("location"), events)
    }

    @Test
    fun `the battery row names the phone makers, says it is unchecked, and opens the app settings`() {
        show(state(allGood))

        for (maker in listOf("Samsung", "Xiaomi", "Redmi", "Vivo", "Oppo", "Realme")) {
            assertTrue(maker, compose.onAllNodesWithText(maker, substring = true).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithText(string(R.string.sos_setup_battery_note)).assertExists()
        compose.onNodeWithText(string(R.string.sos_setup_open_app_settings)).performScrollTo().performClick()
        assertEquals(listOf("app"), events)
    }

    @Test
    fun `it offers a practice run`() {
        show(state(allGood))

        compose.onAllNodesWithText(string(R.string.sos_arm_practice))[1].performScrollTo().performClick()
        assertEquals(listOf("practice"), events)
    }

    // --- the alert settings (P014c2b) ----------------------------------------------------

    @Test
    fun `without a name it says what the alert says instead, and a name can be typed and saved`() {
        show(state(allGood))

        compose.onNodeWithText(string(R.string.sos_setup_name_none)).assertExists()
        compose.onNodeWithText(string(R.string.sos_setup_name_explain)).assertExists()
        compose.onNodeWithText(string(R.string.sos_setup_name_edit)).performScrollTo().performClick()
        // Nothing to remove yet.
        compose.onAllNodesWithText(string(R.string.sos_setup_name_remove)).assertCountEquals(0)
        compose.onNodeWithTag(SosSetupNameFieldTag).performScrollTo().performTextInput("Test User")
        compose.onNodeWithText(string(R.string.sos_setup_name_save)).performScrollTo().performClick()

        assertEquals(listOf("name:Test User"), events)
        compose.onNodeWithTag(SosSetupNameFieldTag).assertDoesNotExist()
    }

    @Test
    fun `with a name it shows how the alert begins, and the name can be changed or removed`() {
        show(state(allGood, settings = SosSettings(name = "Test User")))

        compose.onNodeWithText(string(R.string.sos_setup_name_set, "Test User")).assertExists()
        compose.onNodeWithText(string(R.string.sos_setup_name_change)).performScrollTo().performClick()
        compose.onNodeWithTag(SosSetupNameFieldTag).assertTextContains("Test User")
        compose.onNodeWithText(string(R.string.sos_setup_name_remove)).performScrollTo().performClick()

        assertEquals(listOf("name:removed"), events)
    }

    @Test
    fun `cancel in the name dialog saves nothing, and a long name is cut to what a message carries`() {
        show(state(allGood))

        compose.onNodeWithText(string(R.string.sos_setup_name_edit)).performScrollTo().performClick()
        compose.onNodeWithTag(SosSetupNameFieldTag).performScrollTo().performTextInput("x".repeat(80))
        compose.onNodeWithTag(SosSetupNameFieldTag).assertTextContains("x".repeat(30))
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performScrollTo().performClick()
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithText(string(R.string.sos_setup_name_edit)).performScrollTo().performClick()
        compose.onNodeWithTag(SosSetupNameFieldTag).performScrollTo().performTextInput("y".repeat(80))
        compose.onNodeWithText(string(R.string.sos_setup_name_save)).performScrollTo().performClick()
        assertEquals(listOf("name:" + "y".repeat(30)), events)
    }

    @Test
    fun `the language of the alert is one of three choices, each a button of at least 48 dp`() {
        show(state(allGood, bengali = true, settings = SosSettings(language = AlertLanguageChoice.EN)))

        compose.onNodeWithText(string(R.string.sos_setup_language_en)).performScrollTo().assertIsSelected()
        // "Same as SafeRoute" says which language that is right now.
        val same = compose.onNodeWithText(string(R.string.sos_setup_language_app_bn))
        same.performScrollTo().assertIsNotSelected().assertHasClickAction()
        assertTrue(same.fetchSemanticsNode().size.height / same.fetchSemanticsNode().layoutInfo.density.density >= 48f)
        same.performClick()
        compose.onNodeWithText(string(R.string.sos_setup_language_bn)).performScrollTo().performClick()

        assertEquals(listOf("language:APP", "language:BN"), events)
    }

    @Test
    fun `the countdown sound is off unless switched on, and says the risk of a noise`() {
        show(state(allGood))

        compose.onNodeWithText(string(R.string.sos_setup_sound_off)).assertExists()
        compose.onNodeWithText(string(R.string.sos_setup_sound_warning)).assertExists()
        compose.onNodeWithTag(SosSetupSoundSwitchTag).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("sound:true"), events)

        show(state(allGood, settings = SosSettings(countdownSound = true)))
        compose.onNodeWithText(string(R.string.sos_setup_sound_on)).assertExists()
        compose.onNodeWithTag(SosSetupSoundSwitchTag).assertIsOn()
    }

    @Test
    fun `the alert settings are never counted as things to fix`() {
        show(state(allGood, settings = SosSettings()))
        compose.onNodeWithTag(SosSetupSummaryTag).assertTextContains(string(R.string.sos_setup_ready))
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali at double font size every fix and the practice button can still be reached`() {
        show(
            state(allGood.copy(alertsSetUp = false, location = ReadinessLocation.SWITCHED_OFF, notificationsAllowed = false)),
            fontScale = 2f,
        )

        compose.onNodeWithTag(SosSetupSummaryTag).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_alerts_read_notice)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_refresh)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_open_location_settings)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_open_notification_settings)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.sos_arm_practice))[1].performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("practice"), events)
        // The emergency number is written out in the text, in Latin digits, in Bengali too.
        assertTrue(string(R.string.sos_setup_caveat).contains("112"))
    }

    @Test
    fun `before the first reading it shows a progress bar and no summary`() {
        show(null)

        compose.onNodeWithTag(SosSetupSummaryTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.sos_setup_title)).assertIsDisplayed()
    }
}

/** What the screen may say and do, read from the sources and the string files. */
@RunWith(AndroidJUnit4::class)
class SosSetupRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setupStrings(path: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        val nodes = root.getElementsByTagName("string")
        return List(nodes.length) { nodes.item(it) as Element }
            .filter { it.getAttribute("name").startsWith("sos_setup_") || it.getAttribute("name") == "contacts_open_setup" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun `no text of the screen calls the user or anything safe, or promises that an alert arrives`() {
        val english = setupStrings("src/main/res/values/strings.xml")
        val bengali = setupStrings("src/main/res/values-bn/strings.xml")
        assertTrue("the screen has its strings", english.size > 40)
        assertEquals("the same keys in both languages", english.keys, bengali.keys)

        for ((key, text) in english) {
            assertFalse(key, Regex("""\bsafe\b|\bsafely\b|\bsafety\b""", RegexOption.IGNORE_CASE).containsMatchIn(text.replace("SafeRoute", "")))
            assertFalse(key, Regex("""\b(delivered|guarantee|guaranteed|will arrive|will be read)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        for ((key, text) in bengali) assertFalse(key, text.contains("নিরাপদ"))
        // What "Ready" does not promise is said next to it, with 112.
        assertTrue(english.getValue("sos_setup_caveat").contains("112"))
        assertTrue(bengali.getValue("sos_setup_caveat").contains("112"))
        assertTrue(english.getValue("sos_setup_caveat").contains("not an emergency service"))
    }

    @Test
    fun `the three ways out open the phone's own settings and carry nothing but the app's name`() {
        val app = appDetailsSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.action)
        assertEquals("package:${context.packageName}", app.dataString)
        assertNull(app.extras)

        val notifications = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(context.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(1, notifications.extras?.size())

        val location = locationSettingsIntent()
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, location.action)
        assertNull(location.extras)
        assertNotNull(context)
    }

    @Test
    fun `the screen asks for no permission, asks for no battery exemption, runs nothing in the background and logs nothing`() {
        val sources = listOf("feature/emergency/SosSetup.kt", "feature/emergency/SosSetupScreen.kt", "core/emergency/SosReadiness.kt")
            .map { File("src/main/java/com/saferoute/app/$it") }

        for (source in sources) {
            assertTrue("Not found: ${source.path}", source.isFile)
            val code = source.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")
            val forbidden = listOf(
                "RequestPermission", "requestPermissions", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "WorkManager", "AlarmManager", "startForegroundService",
                "Log.", "println", "Timber", "okhttp", "Retrofit",
            )
            for (word in forbidden) assertFalse("${source.name} must not use $word", code.contains(word))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }
}
