// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.w3c.dom.Element

private const val FAKE_PHONE = "+919000010001"

/**
 * The two places the contacts feature hands over to another app: the contact picker and the
 * SMS app. Robolectric records the intents; no real app is started.
 */
@RunWith(AndroidJUnit4::class)
class ContactIntentsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = application.contentResolver

    /** The invite is started from a screen, so from an activity. */
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val pickedUri: Uri = Uri.parse("content://com.android.contacts/data/phones/1")

    /** Stands in for the phone's contacts provider: answers with what the test set. */
    private class FakePhonesProvider : ContentProvider() {
        var answer: Cursor? = null
        var refuse = false
        val projections = mutableListOf<List<String>>()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? {
            projections += projection.orEmpty().toList()
            if (refuse) throw SecurityException("fake: no access")
            return answer
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    private val provider = FakePhonesProvider().also {
        ShadowContentResolver.registerProviderInternal("com.android.contacts", it)
    }

    private fun cursor(vararg row: String?, columns: Array<String> = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)) =
        MatrixCursor(columns).apply { if (row.isNotEmpty()) addRow(row) }

    // The picker -------------------------------------------------------------------------

    @Test
    fun `the picker intent asks the system to pick one phone number`() {
        val intent = pickPhoneNumberIntent()

        assertEquals(Intent.ACTION_PICK, intent.action)
        assertEquals(Phone.CONTENT_TYPE, intent.type)
        assertNull(intent.extras)
    }

    @Test
    fun `the picked entry is read as a name and a number, asking for those two columns only`() {
        provider.answer = (cursor("  Test Contact 1 ", " 90000 10001 "))

        val picked = readPickedContact(resolver, pickedUri)!!

        assertEquals("Test Contact 1" to "90000 10001", picked.name to picked.number)
        assertEquals("+919000010001", normaliseContactPhone(picked.number))
        assertFalse(picked.toString().contains("Test Contact"))
        assertFalse(picked.toString().contains("10001"))
        assertEquals(listOf(listOf(Phone.DISPLAY_NAME, Phone.NUMBER)), provider.projections)
    }

    @Test
    fun `an entry without a name still gives the number`() {
        provider.answer = (cursor(null, "9000010001"))

        assertEquals("" to "9000010001", readPickedContact(resolver, pickedUri)!!.let { it.name to it.number })
    }

    @Test
    fun `an empty answer, a blank number or missing columns give nothing`() {
        provider.answer = (cursor())
        assertNull(readPickedContact(resolver, pickedUri))

        provider.answer = (cursor("Test Contact 1", "   "))
        assertNull(readPickedContact(resolver, pickedUri))

        provider.answer = (cursor("x", columns = arrayOf("something_else")))
        assertNull(readPickedContact(resolver, pickedUri))

        // No cursor at all (the entry is gone).
        provider.answer = null
        assertNull(readPickedContact(resolver, pickedUri))
    }

    @Test
    fun `a picker that granted no access is a calm nothing, not a crash`() {
        provider.refuse = true

        assertNull(readPickedContact(resolver, pickedUri))
    }

    // The SMS app ------------------------------------------------------------------------

    @Test
    fun `the invite goes to the sms app as text for the user to send`() {
        val message = application.getString(R_INVITE_MESSAGE, FAKE_INVITE_URL)

        assertTrue(openInviteSms(activity, FAKE_PHONE, message))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("smsto", started.data?.scheme)
        assertEquals("smsto:$FAKE_PHONE", started.dataString)
        val body = started.getStringExtra("sms_body")!!
        assertTrue(body.contains(FAKE_INVITE_URL))
        assertTrue(body.contains("opt out here"))
        // Only the body travels as an extra, and the intent names no app of ours.
        assertEquals(setOf("sms_body"), started.extras?.keySet())
        assertNull(started.component)
    }

    @Test
    fun `sending needs no permission, and the app holds none for sms or contacts`() {
        val info = application.packageManager.getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()

        for (permission in listOf("SEND_SMS", "READ_SMS", "RECEIVE_SMS", "READ_CONTACTS", "WRITE_CONTACTS")) {
            assertFalse(permission, requested.any { it.endsWith(".$permission") })
        }
    }

    @Test
    fun `a phone without an sms app is reported, not a crash`() {
        // Robolectric then behaves like Android: starting an intent nobody handles throws.
        shadowOf(application).checkActivities(true)

        assertFalse(openInviteSms(activity, FAKE_PHONE, "text"))
    }

    @Test
    fun `the manifest declares the sms app as visible, and nothing more`() {
        // Gradle runs unit tests with android/app as the working directory.
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        val queries = manifest.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val intents = (queries.item(0) as Element).getElementsByTagName("intent")
        assertEquals(1, intents.length)
        val intent = intents.item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals(
            "android.intent.action.SENDTO",
            (intent.getElementsByTagName("action").item(0) as Element).getAttributeNS(android, "name"),
        )
        assertEquals("smsto", (intent.getElementsByTagName("data").item(0) as Element).getAttributeNS(android, "scheme"))
    }

    // The snooze -------------------------------------------------------------------------

    @Test
    fun `the card's snooze is a point in time in the app's settings file`() = runTest {
        val file = File(application.cacheDir, "contacts-test.preferences_pb").apply { delete() }
        val preferences = DataStoreContactsPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

        assertEquals(0L, preferences.cardSnoozedUntil.first())
        preferences.snoozeCardUntil(1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, preferences.cardSnoozedUntil.first())
    }
}

private val R_INVITE_MESSAGE = com.saferoute.app.R.string.contacts_invite_message
