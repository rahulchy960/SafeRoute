// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone

/*
 * The two moments the contacts feature leaves the app, both started by a tap and both handled
 * by another app the user already trusts:
 *
 * 1. the phone's own contact picker, to choose ONE number;
 * 2. the phone's own SMS app, with an invite ready for the user to send.
 *
 * Neither needs a permission. The app never reads the address book (no READ_CONTACTS) and
 * never sends a message (no SEND_SMS).
 */

/** What the user picked: a name and a number as the address book has them, not yet checked. */
class PickedContact(val name: String, val number: String) {
    override fun toString(): String = "PickedContact(hidden)"
}

/**
 * Opens the system's contact picker on phone numbers. The user sees their contacts in the
 * system's own screen and picks one number; only that one entry comes back to the app.
 *
 * `ACTION_PICK` with the phone-number type is the form Android documents for this
 * ("Select specific contact data"): the answer carries a temporary right to read that single
 * entry, so the app needs no permission.
 */
fun pickPhoneNumberIntent(): Intent = Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)

/**
 * Reads the name and the number of the entry the picker returned, or null when it cannot be
 * read (the entry is gone, the picker app answered with something else, or this phone's
 * picker did not grant access). Nothing else of the address book is queried.
 */
fun readPickedContact(resolver: ContentResolver, uri: Uri): PickedContact? = try {
    resolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val number = cursor.getColumnIndex(Phone.NUMBER).takeIf { it >= 0 }?.let(cursor::getString)
        val name = cursor.getColumnIndex(Phone.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
        if (number.isNullOrBlank()) null else PickedContact(name.orEmpty().trim(), number.trim())
    }
} catch (e: SecurityException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/**
 * The intent that opens the SMS app with [message] ready to send to [phoneE164].
 *
 * `ACTION_SENDTO` with an `smsto:` address goes to an SMS app and nowhere else; `sms_body` is
 * the text the app shows in its message field. The user reads it and taps send, or does not.
 * The number and the text go to the SMS app only.
 */
fun inviteSmsIntent(phoneE164: String, message: String): Intent =
    // Uri.parse, not Uri.fromParts: the latter would write the leading + as %2B. An E.164 number
    // is a + and digits, so it needs no escaping.
    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneE164")).putExtra("sms_body", message)

/** @return false when this phone has no app that can write an SMS. */
fun openInviteSms(context: Context, phoneE164: String, message: String): Boolean = try {
    context.startActivity(inviteSmsIntent(phoneE164, message))
    true
} catch (e: ActivityNotFoundException) {
    false
}
