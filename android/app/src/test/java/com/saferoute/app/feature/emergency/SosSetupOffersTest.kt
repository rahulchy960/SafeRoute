// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.MainActivity
import com.saferoute.app.R
import com.saferoute.app.core.data.SosContact
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.FakeActiveSosContacts
import com.saferoute.app.core.emergency.FakeSmsModeSource
import com.saferoute.app.core.emergency.FakeSosAlertPolicy
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SosEngine
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.contacts.CARD_SNOOZE_DAYS
import com.saferoute.app.feature.contacts.FakeContactsPreferences
import com.saferoute.app.feature.contacts.FakeContactsRepository
import com.saferoute.app.feature.contacts.fakeContact
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val NOW: Instant = Instant.parse("2026-10-13T12:00:00Z")

/** A clock the test moves by hand. */
private class MovableClock(var now: Instant = NOW) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/** The one-time offer on the Emergency contacts screen (P014c2c). */
@OptIn(ExperimentalCoroutinesApi::class)
class SosSetupOfferViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeContactsRepository()
    private val preferences = FakeContactsPreferences()

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `it is offered once a contact exists, and never again after an answer`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SosSetupOfferViewModel(repository, preferences)
        backgroundScope.launch { viewModel.visible.collect {} }
        advanceUntilIdle()
        assertFalse("no contact, no offer", viewModel.visible.value)

        repository.set(fakeContact(1))
        advanceUntilIdle()
        assertTrue(viewModel.visible.value)

        viewModel.onAnswered()
        advanceUntilIdle()
        assertFalse(viewModel.visible.value)
        assertTrue(preferences.setupOfferShown.value)

        // A second contact, or a new screen, does not bring it back.
        repository.set(fakeContact(1), fakeContact(2))
        val later = SosSetupOfferViewModel(repository, preferences)
        backgroundScope.launch { later.visible.collect {} }
        advanceUntilIdle()
        assertFalse(later.visible.value)
        // Nothing was asked of the server for it.
        assertEquals(emptyList<String>(), repository.calls.toList())
    }
}

/** The card on Home: when it shows, what it counts, and that it looks only when asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosSetupCardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val policy = FakeSosAlertPolicy(open = true)
    private val contacts = FakeActiveSosContacts(listOf(SosContact("id-1", "Test Contact 1", "+919000010001")))
    private val preferences = FakeContactsPreferences()
    private val smsMode = FakeSmsModeSource(SmsMode.AUTOMATIC)
    private val environment = FakeLocationEnvironment(granted = GrantedLocation.Precise)
    private val gate = FakeNotificationGate()
    private val clock = MovableClock()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SosSetupCardViewModel {
        Dispatchers.setMain(dispatcher)
        runBlocking { preferences.setContactsSyncedAt(NOW.toEpochMilli()) }
        val reader = SosReadinessReader(policy, contacts, preferences, smsMode, environment, gate, clock)
            .also { it.smsDeclared = true }
        return SosSetupCardViewModel(reader, preferences, clock)
    }

    @Test
    fun `with everything in order Home shows nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals("nothing before Home is in front", 0, viewModel.toFix.value)

        viewModel.onResume()
        advanceUntilIdle()

        assertEquals(0, viewModel.toFix.value)
    }

    @Test
    fun `it counts what is to fix, and looks again only when Home comes to the front`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onResume()
        advanceUntilIdle()
        val checks = gate.checks

        // Things change while Home is shown: nothing watches them.
        environment.granted = GrantedLocation.Approximate
        gate.block = NotificationBlock.PermissionMissing
        advanceUntilIdle()
        assertEquals(0, viewModel.toFix.value)
        assertEquals("no look in between", checks, gate.checks)

        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(2, viewModel.toFix.value)

        // Fixed in the phone's settings, back on Home: the card goes.
        environment.granted = GrantedLocation.Precise
        gate.block = NotificationBlock.None
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(0, viewModel.toFix.value)
    }

    @Test
    fun `a user who agreed to an older notice is told on Home`() = runTest(dispatcher) {
        // Contacts on the phone, and the gate closed: no SOS can start (a new notice version).
        policy.open = false
        val viewModel = viewModel()

        viewModel.onResume()
        advanceUntilIdle()

        assertEquals(1, viewModel.toFix.value)
    }

    @Test
    fun `before SOS is being set up at all the card stays away`() = runTest(dispatcher) {
        // No agreement and no contact: "Add emergency contacts" is the card for that.
        policy.open = false
        contacts.list = emptyList()
        environment.granted = GrantedLocation.None
        val viewModel = viewModel()

        viewModel.onResume()
        advanceUntilIdle()

        assertEquals(0, viewModel.toFix.value)
    }

    @Test
    fun `not now hides it for three days and no longer`() = runTest(dispatcher) {
        gate.block = NotificationBlock.AppBlocked
        val viewModel = viewModel()
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(1, viewModel.toFix.value)

        viewModel.onNotNow()
        advanceUntilIdle()
        assertEquals(0, viewModel.toFix.value)
        assertEquals(NOW.plus(Duration.ofDays(CARD_SNOOZE_DAYS)).toEpochMilli(), preferences.setupCardSnoozedUntil.value)

        clock.now = NOW.plus(Duration.ofDays(CARD_SNOOZE_DAYS)).minusSeconds(1)
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals("still put off", 0, viewModel.toFix.value)

        clock.now = NOW.plus(Duration.ofDays(CARD_SNOOZE_DAYS))
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals("it comes back while something is still to fix", 1, viewModel.toFix.value)
    }
}

/** The card as a picture, and what its code may not do. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SosSetupCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()
    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun show(title: String, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SafeRouteTheme {
                    SetupCard(
                        title = title,
                        body = string(R.string.sos_setup_home_body),
                        onOpen = { events += "open" },
                        onNotNow = { events += "not now" },
                    )
                }
            }
        }
    }

    @Test
    fun `it says how many things are to fix and offers the checklist and not now`() {
        show(string(R.string.sos_setup_not_ready_many, 3))

        compose.onNodeWithText(string(R.string.sos_setup_not_ready_many, 3)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_home_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_open)).assertHasClickAction().performClick()
        compose.onNodeWithText(string(R.string.contacts_card_not_now)).assertHasClickAction().performClick()
        assertEquals(listOf("open", "not now"), events)
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali at double font size both buttons can be used`() {
        show(string(R.string.sos_setup_not_ready_one), fontScale = 2f)

        compose.onNodeWithText(string(R.string.sos_setup_open)).assertIsDisplayed().performClick()
        compose.onNodeWithText(string(R.string.contacts_card_not_now)).assertIsDisplayed().performClick()
        assertEquals(listOf("open", "not now"), events)
    }

    @Test
    fun `the cards and the reader run nothing in the background, ask for nothing and log nothing`() {
        for (name in listOf("SosSetupOffers.kt", "SosReadinessReader.kt")) {
            val code = File("src/main/java/com/saferoute/app/feature/emergency/$name").readLines()
                .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
                .joinToString("\n")
            val forbidden = listOf(
                "WorkManager", "AlarmManager", "startService", "startForegroundService", "@ApplicationScope",
                "RequestPermission", "requestPermissions", "LocationRepository", "Log.", "println", "Timber",
                "okhttp", "Retrofit",
            )
            for (word in forbidden) assertFalse("$name must not use $word", code.contains(word))
        }
        // No text of the two ways to the checklist says "safe".
        for (id in listOf(R.string.sos_setup_offer_title, R.string.sos_setup_offer_body, R.string.sos_setup_home_body, R.string.sos_setup_open)) {
            assertFalse(Regex("""\bsafe\b""", RegexOption.IGNORE_CASE).containsMatchIn(string(id).replace("SafeRoute", "")))
        }
    }
}

/** The two ways to the checklist in the real app. */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class SosSetupOffersFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject lateinit var contacts: FakeContactsRepository

    @Inject lateinit var preferences: FakeContactsPreferences

    @Inject lateinit var policy: FakeSosAlertPolicy

    @Inject lateinit var engine: SosEngine

    @Before
    fun inject() {
        hilt.inject()
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openSheet() {
        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description)).performClick()
        compose.waitForIdle()
    }

    /** Home comes to the front again, as after a visit to another screen or to Settings. */
    private fun resumeHome() {
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun `by default in tests, and for a user who has not begun, Home shows no such card`() {
        openSheet()

        compose.onNodeWithTag(SosSetupHomeCardTag).assertDoesNotExist()
    }

    @Test
    fun `while not ready Home leads to the checklist, with the SOS control still there`() {
        // Agreed to the notice, but nothing else is in order on this (fake) phone.
        policy.open = true
        preferences.clearSetupOffers()
        resumeHome()
        openSheet()
        waitForTag(SosSetupHomeCardTag)

        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_setup_home_body)).assertExists()
        compose.onNodeWithText(string(R.string.sos_setup_open)).performClick()

        waitForText(string(R.string.sos_setup_title))
        waitForTag(SosSetupSummaryTag)
        // Looking at the card and opening the checklist starts nothing.
        assertNull(shadowOf(compose.activity.application).nextStartedActivity)
        assertNull(runBlocking { engine.current() })
    }

    @Test
    fun `not now on Home puts the card away`() {
        policy.open = true
        preferences.clearSetupOffers()
        resumeHome()
        openSheet()
        waitForTag(SosSetupHomeCardTag)

        compose.onNodeWithText(string(R.string.contacts_card_not_now)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SosSetupHomeCardTag).assertDoesNotExist()
        assertTrue(preferences.setupCardSnoozedUntil.value > 0)
        resumeHome()
        compose.onNodeWithTag(SosSetupHomeCardTag).assertDoesNotExist()
    }

    @Test
    fun `after the first contact the contacts screen offers the checklist once`() {
        policy.open = true
        contacts.consentGranted = true
        contacts.set(fakeContact(1))
        preferences.clearSetupOffers()

        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.settings_contacts_title)).performScrollTo().performClick()
        waitForTag(SosSetupOfferTag)
        compose.onNodeWithText(string(R.string.sos_setup_offer_title)).assertExists()

        compose.onNodeWithText(string(R.string.contacts_card_not_now)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SosSetupOfferTag).assertDoesNotExist()
        assertTrue(preferences.setupOfferShown.value)

        // The plain button to the checklist is still there, and the offer does not return.
        compose.onNodeWithText(string(R.string.contacts_open_setup)).performScrollTo().performClick()
        waitForText(string(R.string.sos_setup_title))
        compose.onNodeWithContentDescription(string(R.string.navigate_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SosSetupOfferTag).assertDoesNotExist()
    }

    @Test
    fun `opening the checklist by its own button answers the offer too`() {
        policy.open = true
        contacts.consentGranted = true
        contacts.set(fakeContact(1))
        preferences.clearSetupOffers()

        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.settings_contacts_title)).performScrollTo().performClick()
        waitForTag(SosSetupOfferTag)
        compose.onNodeWithText(string(R.string.contacts_open_setup)).performScrollTo().performClick()
        waitForText(string(R.string.sos_setup_title))

        assertTrue(preferences.setupOfferShown.value)
    }
}
