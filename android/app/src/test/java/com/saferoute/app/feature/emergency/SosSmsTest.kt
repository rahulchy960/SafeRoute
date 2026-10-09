// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.BuildConfig
import com.saferoute.app.core.emergency.SmsOutcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSmsManager

// A number from the fake range; nothing is sent anywhere in a JVM test.
private const val FAKE_NUMBER = "+919000010001"

/** The one place that can send an SMS: what it refuses, what it sends and how it reads the answer. */
@RunWith(AndroidJUnit4::class)
class SosSmsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val gateway = AndroidSmsGateway(application)

    @Test
    fun `only an OK from the phone counts as sent`() {
        assertEquals(SmsOutcome.Sent, smsOutcome(Activity.RESULT_OK))
        // Everything Android can report, and a code that does not exist yet.
        for (code in (0..40) + (100..125) + 999) {
            assertFalse("code $code", smsOutcome(code) == SmsOutcome.Sent)
        }
    }

    @Test
    fun `what may pass is retried and what cannot is final`() {
        val retryable = mapOf(
            SmsManager.RESULT_ERROR_GENERIC_FAILURE to "generic",
            SmsManager.RESULT_ERROR_RADIO_OFF to "radio_off",
            SmsManager.RESULT_RADIO_NOT_AVAILABLE to "radio_off",
            SmsManager.RESULT_ERROR_NO_SERVICE to "no_service",
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED to "limit",
            SmsManager.RESULT_ERROR_NULL_PDU to "generic",
            SmsManager.RESULT_NETWORK_ERROR to "generic",
            SmsManager.RESULT_RIL_SIM_ABSENT to "sim_absent",
            SmsManager.RESULT_RIL_NETWORK_NOT_READY to "no_service",
        )
        for ((code, category) in retryable) {
            assertEquals("code $code", SmsOutcome.Retryable(category), smsOutcome(code))
        }
        for (code in listOf(
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
            SmsManager.RESULT_INVALID_ARGUMENTS,
            SmsManager.RESULT_INVALID_SMS_FORMAT,
            SmsManager.RESULT_ENCODING_ERROR,
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE,
            SmsManager.RESULT_USER_NOT_ALLOWED,
        )) {
            assertEquals("code $code", SmsOutcome.Final("rejected"), smsOutcome(code))
        }
    }

    @Test
    fun `without the permission it refuses and never reaches the phone's SMS service`() {
        assertFalse(canSendSmsAutomatically(application))

        val outcome = runBlocking { gateway.send(FAKE_NUMBER, "Test message") }

        assertEquals(SmsOutcome.Final("no_permission"), outcome)
        val sms: ShadowSmsManager = shadowOf(application.getSystemService(SmsManager::class.java))
        assertNull(sms.lastSentMultipartTextMessageParams)
        assertNull(sms.lastSentTextMessageParams)
    }

    @Test
    fun `it hands one multipart message to the phone and waits for an answer per part`() {
        ShadowLog.clear()
        val manager = application.getSystemService(SmsManager::class.java)
        val sms: ShadowSmsManager = shadowOf(manager)
        val parts = arrayListOf("Test message, part one. ", "Test message, part two.")
        var outcome: SmsOutcome? = null

        val sender = Thread { outcome = runBlocking { gateway.sendParts(manager, FAKE_NUMBER, parts) } }.apply { start() }
        val deadline = System.currentTimeMillis() + 5_000
        while (sms.lastSentMultipartTextMessageParams == null && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val sent = sms.lastSentMultipartTextMessageParams
        assertEquals(FAKE_NUMBER, sent.destinationAddress)
        assertNull("the network's own service centre is used", sent.scAddress)
        assertEquals(parts, sent.parts)
        assertEquals("one answer is asked for per part", 2, sent.sentIntents.size)
        assertNull("no delivery reports are asked for", sent.deliveryIntents)
        assertTrue(sent.sentIntents.all { shadowOf(it).isImmutable && shadowOf(it).isBroadcast })
        // The answer goes to this app only.
        assertTrue(sent.sentIntents.all { shadowOf(it).savedIntent.`package` == application.packageName })
        // Until the phone has answered for every part, the sender is still waiting.
        assertNull(outcome)

        sender.interrupt()
        sender.join(2_000)
        // Nothing about the message was written to the log.
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg}" }
        assertFalse(logged.contains("9000010001"))
        assertFalse(logged.contains("Test message"))
    }

    @Test
    fun `on a device that cannot send SMS it says so instead of crashing`() {
        // The JVM test device is such a device: Android answers "Sms is not supported".
        assumeTrue(BuildConfig.SEND_SMS_DECLARED)
        shadowOf(application).grantPermissions(Manifest.permission.SEND_SMS)
        assertTrue(canSendSmsAutomatically(application))

        val outcome = runBlocking { gateway.send(FAKE_NUMBER, "Test message") }

        assertEquals(SmsOutcome.Final("no_telephony"), outcome)
    }

    @Test
    fun `the main manifest never asks for SEND_SMS, only the extra one of builds made with it`() {
        fun permissions(path: String) = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(File(path).readText()).map { it.groupValues[1] }.toList()

        assertTrue(permissions("src/main/AndroidManifest.xml").none { it.contains("SMS") })
        assertEquals(emptyList<String>(), permissions("src/sendSmsOff/AndroidManifest.xml"))
        assertEquals(listOf("android.permission.SEND_SMS"), permissions("src/sendSmsOn/AndroidManifest.xml"))
    }

    @Test
    fun `this build asks for SEND_SMS exactly when it was built with it`() {
        val requested = application.packageManager
            .getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()

        assertEquals(BuildConfig.SEND_SMS_DECLARED, Manifest.permission.SEND_SMS in requested)
        // It can send; it can never read.
        assertFalse(Manifest.permission.READ_SMS in requested)
        assertFalse(Manifest.permission.RECEIVE_SMS in requested)
    }

    @Test
    fun `only the gateway file names the phone's SMS service`() {
        val users = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { it.contains("android.telephony.SmsManager") } }
            .map { it.name }
            .toList()

        assertEquals(listOf("SosSms.kt"), users)
    }
}
