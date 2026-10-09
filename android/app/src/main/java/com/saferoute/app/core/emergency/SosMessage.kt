// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The text of the alert messages (ADR 0027, note of P014b1). Plain Kotlin: no Android, no
 * clock of its own, nothing stored and nothing logged. The wording is a DRAFT until a lawyer
 * has reviewed it, and the Bengali is a draft until a native speaker has; both are mirrored
 * in the SMS text document under docs/legal (the file name is in SosSmsTextDocumentTest).
 *
 * Numbers are always written in Latin digits, also in the Bengali text: 112 must read 112.
 */

/** The language of the message, chosen by the sender. The receiver may read another. */
enum class SosLanguage { EN, BN }

/** A message is cut down until it fits this many SMS parts. */
const val MAX_SMS_SEGMENTS = 3

/** A longer name is cut: the message has to stay short, and a name is not the point. */
const val MAX_NAME_LENGTH = 30

private const val GSM_SINGLE = 160
private const val GSM_PART = 153
private const val UCS2_SINGLE = 70
private const val UCS2_PART = 67
private const val SECONDS_PER_MINUTE = 60L

/** The basic character set of SMS (GSM 03.38): each of these costs one of 160 places. */
private const val GSM_BASIC =
    "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

/** These exist in SMS too, but each costs two places. */
private const val GSM_EXTENDED = "^{}\\[~]|€"

/**
 * How many SMS parts [text] needs. A text made only of the SMS alphabet fits 160 characters
 * in one part (153 per part when split). One character from outside it, for example any
 * Bengali letter, switches the WHOLE message to 16-bit characters: 70 in one part, 67 per
 * part when split. That is why a Bengali message is short.
 *
 * The phone's own split (`divideMessage`) is what is really sent; this count is for keeping
 * the text inside [MAX_SMS_SEGMENTS] before it gets there.
 */
fun smsSegments(text: String): Int {
    if (text.isEmpty()) return 0
    var septets = 0
    for (char in text) {
        septets += when (char) {
            in GSM_BASIC -> 1
            in GSM_EXTENDED -> 2
            else -> return if (text.length <= UCS2_SINGLE) 1 else (text.length + UCS2_PART - 1) / UCS2_PART
        }
    }
    return if (septets <= GSM_SINGLE) 1 else (septets + GSM_PART - 1) / GSM_PART
}

/**
 * A name as it may appear in a message: one line, no control characters, at most
 * [MAX_NAME_LENGTH] characters. Null when nothing readable is left.
 */
fun messageName(raw: String?, maxLength: Int = MAX_NAME_LENGTH): String? =
    raw.orEmpty()
        .map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()
        .take(maxLength)
        .trim()
        .ifEmpty { null }

/** Everything an alert says. Hides itself: it holds a name and a position. */
class SosMessageInput(
    /** The sender's name for alerts; null when none was set. */
    val name: String?,
    val language: SosLanguage,
    /** The newest position, or null when there is none. */
    val point: SosPoint?,
    val now: Instant,
    val batteryPercent: Int?,
) {
    override fun toString(): String = "SosMessageInput(hidden)"
}

private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * A link that the receiver's phone opens in its map app, with the coordinates readable in
 * the link itself for a phone that cannot open links. Five decimals are about one metre.
 * The app sends nothing to the map provider: the link is text in an SMS.
 */
private fun mapLink(point: SosPoint): String =
    String.format(Locale.ROOT, "https://maps.google.com/?q=%.5f,%.5f", point.latitude, point.longitude)

private fun who(name: String?, language: SosLanguage): String = when (language) {
    SosLanguage.EN -> name?.let { "$it needs help." }
        ?: "Someone who listed you as an emergency contact needs help."
    SosLanguage.BN -> name?.let { "$it-এর সাহায্য দরকার।" }
        ?: "যিনি আপনাকে জরুরি পরিচিতি করেছেন, তাঁর সাহায্য দরকার।"
}

private fun where(input: SosMessageInput): String {
    val point = input.point ?: return when (input.language) {
        SosLanguage.EN -> "Location unavailable."
        SosLanguage.BN -> "অবস্থান পাওয়া যায়নি।"
    }
    val time = CLOCK_TIME.format(point.recordedAt.atZone(INDIA))
    val ageMinutes = Duration.between(point.recordedAt, input.now).seconds / SECONDS_PER_MINUTE
    val accuracy = point.accuracyMeters?.let { Math.round(it) }
    return when (input.language) {
        SosLanguage.EN -> buildString {
            append("Location: ").append(mapLink(point)).append(" (")
            if (accuracy != null) append("about ").append(accuracy).append(" m, ")
            append("at ").append(time).append(" IST")
            if (ageMinutes >= 1) append(", ").append(ageMinutes).append(" min old")
            append(").")
        }
        SosLanguage.BN -> buildString {
            append("অবস্থান: ").append(mapLink(point)).append(" (")
            if (accuracy != null) append("প্রায় ").append(accuracy).append(" মি, ")
            append(time).append(" IST")
            if (ageMinutes >= 1) append(", ").append(ageMinutes).append(" মিনিট আগের")
            append(")।")
        }
    }
}

private fun battery(percent: Int?, language: SosLanguage): String? = percent?.let {
    when (language) {
        SosLanguage.EN -> "Battery $it%."
        SosLanguage.BN -> "ব্যাটারি $it%।"
    }
}

private fun sentBy(language: SosLanguage): String = when (language) {
    SosLanguage.EN -> "Sent by the SafeRoute app."
    SosLanguage.BN -> "SafeRoute অ্যাপ থেকে পাঠানো।"
}

private fun call112(language: SosLanguage): String = when (language) {
    SosLanguage.EN -> "If you think they are in danger, call 112."
    SosLanguage.BN -> "তাঁরা বিপদে আছেন মনে হলে 112-এ কল করুন।"
}

/**
 * The alert. If the full text needs more than [MAX_SMS_SEGMENTS] parts it is shortened in
 * this order: the battery goes, then "Sent by the SafeRoute app", then the name is cut
 * shorter. Who needs help, where they are and "call 112" always stay.
 */
fun buildAlertMessage(input: SosMessageInput): String {
    val name = messageName(input.name)
    val place = where(input)
    val help = call112(input.language)
    fun text(shownName: String?, withBattery: Boolean, withSender: Boolean) = listOfNotNull(
        who(shownName, input.language),
        place,
        battery(input.batteryPercent, input.language).takeIf { withBattery },
        sentBy(input.language).takeIf { withSender },
        help,
    ).joinToString(" ")

    val candidates = sequence {
        yield(text(name, withBattery = true, withSender = true))
        yield(text(name, withBattery = false, withSender = true))
        yield(text(name, withBattery = false, withSender = false))
        var shorter = name
        while (shorter != null && shorter.length > 1) {
            shorter = messageName(shorter, shorter.length / 2)
            yield(text(shorter, withBattery = false, withSender = false))
        }
        yield(text(null, withBattery = false, withSender = false))
    }
    return candidates.firstOrNull { smsSegments(it) <= MAX_SMS_SEGMENTS }
        ?: text(null, withBattery = false, withSender = false)
}

/** One follow-up when a better position arrived shortly after the alert. */
fun buildLocationUpdateMessage(input: SosMessageInput): String {
    val name = messageName(input.name)
    val lead = when (input.language) {
        SosLanguage.EN -> name?.let { "Update from $it." } ?: "Update to the alert."
        SosLanguage.BN -> name?.let { "$it-এর নতুন তথ্য।" } ?: "সতর্কবার্তার নতুন তথ্য।"
    }
    return "$lead ${where(input)}"
}

/** The follow-up after "I'm safe". It reports what the sender said, not a fact. */
fun buildSafeMessage(rawName: String?, language: SosLanguage): String {
    val name = messageName(rawName)
    return when (language) {
        SosLanguage.EN -> name?.let { "$it says they are safe now." }
            ?: "The person who alerted you says they are safe now."
        SosLanguage.BN -> name?.let { "$it জানিয়েছেন যে তিনি এখন নিরাপদ।" }
            ?: "যিনি আপনাকে সতর্ক করেছিলেন, তিনি জানিয়েছেন যে তিনি এখন নিরাপদ।"
    } + " " + sentBy(language)
}
