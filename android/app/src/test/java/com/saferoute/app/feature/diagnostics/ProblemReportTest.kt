// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.diagnostics.ApiErrorKind
import com.saferoute.app.core.diagnostics.DiagnosticEvent
import com.saferoute.app.core.diagnostics.DiagnosticKey
import com.saferoute.app.core.diagnostics.DiagnosticsLog
import com.saferoute.app.core.diagnostics.DiagnosticsSanitizer
import com.saferoute.app.core.diagnostics.ErrorCategory
import com.saferoute.app.core.diagnostics.FakeDiagnosticsReporter
import com.saferoute.app.core.diagnostics.ProblemReportInput
import com.saferoute.app.core.diagnostics.RecordingCrashBackend
import com.saferoute.app.core.diagnostics.RedactingDiagnosticsReporter
import com.saferoute.app.core.diagnostics.SmsFailure
import com.saferoute.app.core.diagnostics.problemReportText
import com.saferoute.app.core.diagnostics.relativeTime
import com.saferoute.app.core.emergency.FakeSmsRecipientLimit
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SmsOutcome
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.network.interceptor.DiagnosticsInterceptor
import com.saferoute.app.core.network.interceptor.apiErrorKind
import com.saferoute.app.feature.emergency.AndroidSmsGateway
import com.saferoute.app.feature.emergency.FakeNotificationGate
import com.saferoute.app.feature.emergency.NotificationBlock
import com.saferoute.app.feature.onboarding.ProblemScreen
import com.saferoute.app.feature.settings.SettingsScreen
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Report a problem" (ADR 0028): what the text holds, what the two buttons hand over, and
 * where the categories in it come from. Every number, name and place below is made up.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
@OptIn(ExperimentalCoroutinesApi::class)
class ProblemReportTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun string(id: Int): String = context.getString(id)

    private fun input(events: List<DiagnosticsLog.Entry> = emptyList()) = ProblemReportInput(
        appVersion = "1.0",
        buildType = "debug",
        make = "TestMaker",
        model = "Model 1",
        androidVersion = "16 (API 36)",
        facts = mapOf(
            DiagnosticKey.LOCALE to "bn",
            DiagnosticKey.NETWORK_TYPE to "cellular",
            DiagnosticKey.LOCATION_PERMISSION to "approximate",
            DiagnosticKey.SMS_PERMISSION to "denied",
            DiagnosticKey.NOTIFICATION_PERMISSION to "granted",
            DiagnosticKey.SMS_MODE to "composer",
        ),
        readiness = listOf("alerts" to "ok", "contacts" to "needs_action", "battery" to "cannot_know"),
        toFix = 1,
        events = events,
        now = now,
    )

    @Composable
    private fun Themed(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme(content = content)
        }
    }

    // --- The text ---

    @Test
    fun `the report holds versions, the phone, state words and categories with relative times`() {
        val events = listOf(
            DiagnosticsLog.Entry("API_ERROR(unavailable)", now.minusSeconds(3 * 3600)),
            DiagnosticsLog.Entry("SMS_SEND_FAILED(no_service)", now.minusSeconds(5 * 60)),
            DiagnosticsLog.Entry("RECOVERY_SHOWN", now.minusSeconds(10)),
        )

        val text = problemReportText(input(events))

        assertEquals(
            """
            SafeRoute problem report
            App: 1.0 (debug)
            Phone: TestMaker Model 1
            Android: 16 (API 36)
            locale: bn
            networkType: cellular

            Permissions and SMS:
            - locationPermission: approximate
            - smsPermission: denied
            - notificationPermission: granted
            - smsMode: composer

            SOS setup: not ready, 1 to fix
            - alerts: ok
            - contacts: needs_action
            - battery: cannot_know

            Recent events (newest first):
            - just now: RECOVERY_SHOWN
            - 5 min ago: SMS_SEND_FAILED(no_service)
            - 3 h ago: API_ERROR(unavailable)
            """.trimIndent(),
            text,
        )
        // No time of day, no date and no time zone: only how long ago.
        assertFalse(text.contains("2026") || text.contains("12:00") || text.contains("Z:"))
    }

    @Test
    fun `without events and with a note the report says so and ends with the note`() {
        val text = problemReportText(input().copy(toFix = 0, facts = emptyMap()), note = "  it closed on the map  ")

        assertTrue(text.contains("Recent events: none since the app was started"))
        assertTrue(text.contains("SOS setup: ready"))
        assertTrue(text.contains("- smsMode: unknown"))
        assertTrue(text.endsWith("Note from the tester:\nit closed on the map"))
        assertFalse(problemReportText(input(), note = "   ").contains("Note from the tester"))
    }

    @Test
    fun `relative times never go below zero and grow to days`() {
        assertEquals("just now", relativeTime(now.plusSeconds(60), now))
        assertEquals("59 min ago", relativeTime(now.minusSeconds(59 * 60), now))
        assertEquals("1 h ago", relativeTime(now.minusSeconds(3600), now))
        assertEquals("2 d ago", relativeTime(now.minusSeconds(2 * 86_400), now))
    }

    @Test
    fun `what was reported with personal text reaches the list as a category only`() {
        val log = DiagnosticsLog(clock)
        val reporter = RedactingDiagnosticsReporter(RecordingCrashBackend(), log)
        val number = "+910000100001"
        reporter.report(IllegalStateException("cannot parse $number near 22.5, 88.25"), ErrorCategory.STORAGE)
        reporter.report(DiagnosticEvent.SmsSendFailed(SmsFailure.fromWord(number)))
        reporter.setKey(DiagnosticKey.OEM_FAMILY, "Test Contact 1")

        val text = problemReportText(input(log.entries()))

        assertTrue(text.contains("ERROR(STORAGE)") && text.contains("SMS_SEND_FAILED(other)"))
        assertFalse(text.contains(number) || text.contains("22.5") || text.contains("Test Contact"))
        // Every line the app itself wrote passes the filter for personal text.
        for (line in text.lines().filter { it.startsWith("- ") }) {
            assertFalse(line, DiagnosticsSanitizer.looksPersonal(line.substringAfter(": ")))
        }
    }

    // --- The phone's facts ---

    @Test
    fun `phone makers are folded into families and anything else is other`() {
        assertEquals("samsung", oemFamily("Samsung"))
        assertEquals("xiaomi", oemFamily(" POCO "))
        assertEquals("nokia", oemFamily("HMD Global"))
        assertEquals("other", oemFamily("SM-G990E"))
        assertEquals("other", oemFamily(""))
    }

    @Test
    fun `every fact read from the phone is a word its key accepts`() {
        val environment = FakeLocationEnvironment(granted = GrantedLocation.Approximate)
        val gate = FakeNotificationGate(NotificationBlock.PermissionMissing)
        val facts = runBlocking {
            AndroidDiagnosticsFacts(context, environment, { SmsMode.COMPOSER }, gate).read()
        }

        assertEquals((DiagnosticKey.entries - DiagnosticKey.APP_VERSION - DiagnosticKey.BUILD_TYPE - DiagnosticKey.ANDROID_API).toSet(), facts.keys)
        for ((key, value) in facts) assertEquals("${key.key}=$value", value, key.clean(value))
        assertEquals("approximate", facts[DiagnosticKey.LOCATION_PERMISSION])
        assertEquals("denied", facts[DiagnosticKey.NOTIFICATION_PERMISSION])
        assertEquals("composer", facts[DiagnosticKey.SMS_MODE])
    }

    // --- Where the categories come from ---

    @Test
    fun `a failed text is noted once per reason, and a sent one never`() {
        val reporter = FakeDiagnosticsReporter()
        val gateway = ReportingSmsGateway(AndroidSmsGateway(context, FakeSmsRecipientLimit()), reporter)

        gateway.noteOutcome(SmsOutcome.Sent)
        gateway.noteOutcome(SmsOutcome.Retryable("no_service"))
        gateway.noteOutcome(SmsOutcome.Retryable("no_service"))
        gateway.noteOutcome(SmsOutcome.Final("made-up word"))
        gateway.noteOutcome(SmsOutcome.Sent)
        gateway.noteOutcome(SmsOutcome.Retryable("no_service"))

        assertEquals(
            listOf<DiagnosticEvent>(
                DiagnosticEvent.SmsSendFailed(SmsFailure.NO_SERVICE),
                DiagnosticEvent.SmsSendFailed(SmsFailure.OTHER),
                DiagnosticEvent.SmsSendFailed(SmsFailure.NO_SERVICE),
            ),
            reporter.events,
        )
    }

    @Test
    fun `given-up alerts and a missing position are noted when they begin, and sign-out empties the list`() =
        runTest(UnconfinedTestDispatcher()) {
            val reporter = FakeDiagnosticsReporter()
            val log = DiagnosticsLog(clock)
            val givenUp = MutableStateFlow(false)
            val noPosition = MutableStateFlow(false)
            val left = MutableStateFlow(false)
            observeDiagnostics(backgroundScope, givenUp, noPosition, left, reporter, log)
            assertEquals(emptyList<DiagnosticEvent>(), reporter.events)

            noPosition.value = true
            givenUp.value = true
            givenUp.value = false
            givenUp.value = true
            assertEquals(
                listOf(DiagnosticEvent.LocationUnavailable, DiagnosticEvent.AlertsGivenUp, DiagnosticEvent.AlertsGivenUp),
                reporter.events,
            )

            RedactingDiagnosticsReporter(RecordingCrashBackend(), log).report(DiagnosticEvent.RecoveryShown)
            assertEquals(1, log.entries().size)
            left.value = true
            assertEquals(emptyList<DiagnosticsLog.Entry>(), log.entries())
        }

    @Test
    fun `a server failure is noted as its kind, a result or a success is not`() {
        assertEquals(ApiErrorKind.SERVER_ERROR, apiErrorKind(500))
        assertEquals(ApiErrorKind.UNAVAILABLE, apiErrorKind(503))
        assertEquals(ApiErrorKind.RATE_LIMITED, apiErrorKind(429))
        for (status in listOf(200, 201, 204, 400, 401, 403, 404, 409, 410)) assertNull("$status", apiErrorKind(status))

        val reporter = FakeDiagnosticsReporter()
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(503).body("made-up body").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(DiagnosticsInterceptor(reporter)).build()
            repeat(2) { client.newCall(Request.Builder().url(server.url("/v1/x")).build()).execute().close() }
        } finally {
            server.close()
        }
        assertEquals(listOf<DiagnosticEvent>(DiagnosticEvent.ApiError(ApiErrorKind.UNAVAILABLE)), reporter.events)
    }

    // --- The screen and its two buttons ---

    private fun show(
        emailAvailable: Boolean,
        noEmailApp: Boolean = false,
        fontScale: Float = 1f,
        sent: MutableList<String> = mutableListOf(),
    ) = compose.setContent {
        Themed(fontScale) {
            ProblemReportScreen(
                report = input(),
                emailAvailable = emailAvailable,
                noEmailApp = noEmailApp,
                onBack = { sent += "back" },
                onEmail = { sent += "email:$it" },
                onShare = { sent += "share:$it" },
            )
        }
    }

    @Test
    fun `the screen shows exactly the text that both buttons hand over, note included`() {
        val sent = mutableListOf<String>()
        show(emailAvailable = true, sent = sent)

        compose.onNodeWithTag(ProblemReportTextTag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.report_note_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ProblemReportNoteTag).performScrollTo().performTextInput("it closed")
        compose.onNodeWithText(string(R.string.report_email)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.report_share)).performScrollTo().performClick()

        val expected = problemReportText(input(), "it closed")
        assertEquals(listOf("email:$expected", "share:$expected"), sent)
        compose.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `without a support address there is no email button, only share`() {
        show(emailAvailable = false)

        compose.onNodeWithText(string(R.string.report_email)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.report_share)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.report_no_email_app)).assertDoesNotExist()
    }

    @Test
    fun `a phone without an email app is told to use share, also at twice the font size`() {
        val sent = mutableListOf<String>()
        show(emailAvailable = true, noEmailApp = true, fontScale = 2f, sent = sent)

        compose.onNodeWithText(string(R.string.report_no_email_app)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.report_share)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.navigate_back)).performScrollTo().performClick()
        assertEquals(2, sent.size)
    }

    @Test
    fun `email goes to the address through the mail app, share through the share sheet`() {
        val email = emailReportIntent("support@example.invalid", "subject", "the text")
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals("mailto:support@example.invalid", email.dataString)
        assertEquals("subject", email.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("the text", email.getStringExtra(Intent.EXTRA_TEXT))
        // The text is in the body, never in the address line.
        assertFalse(email.dataString.orEmpty().contains("text"))

        val chooser = shareReportIntent("the text")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, inner?.action)
        assertEquals("text/plain", inner?.type)
        assertEquals("the text", inner?.getStringExtra(Intent.EXTRA_TEXT))
    }

    // --- The ways in ---

    @Test
    fun `the problem screen of onboarding links to the report`() {
        var opened = 0
        compose.setContent { Themed { ProblemScreen(retryable = true, onRetry = {}, onReport = { opened++ }) } }

        compose.onNodeWithText(string(R.string.report_title)).performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `Settings has a row that opens the report`() {
        var opened = 0
        compose.setContent {
            Themed { SettingsScreen(versionName = "1.0", versionCode = 1, onBack = {}, onReportProblem = { opened++ }) }
        }

        compose.onNodeWithText(string(R.string.report_title)).performScrollTo().performClick()

        assertEquals(1, opened)
        compose.onNodeWithText(string(R.string.settings_report_supporting)).assertExists()
    }
}
