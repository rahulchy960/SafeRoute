// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

/**
 * The only facts about the phone and the build that may travel with a crash report, as
 * "custom keys" (ADR 0028). Each key says which values it accepts: a short fixed list of
 * words, or a narrow shape such as a version number. A value that does not fit is dropped.
 *
 * Why a list per key and not only a filter for "bad" text: a filter cannot tell a first
 * name from an ordinary word. A list of the words that are allowed can.
 */
enum class DiagnosticKey(val key: String, private val accepts: (String) -> Boolean) {
    APP_VERSION("appVersion", Regex("""\d{1,4}(\.\d{1,4}){0,3}""")::matches),
    BUILD_TYPE("buildType", setOf("debug", "release")::contains),
    ANDROID_API("androidApi", Regex("""\d{2}""")::matches),
    OEM_FAMILY("oemFamily", OEM_FAMILIES::contains),
    LOCALE("locale", Regex("""[a-z]{2,3}(-[A-Z]{2})?""")::matches),
    NETWORK_TYPE("networkType", setOf("wifi", "cellular", "other", "none")::contains),
    SMS_MODE("smsMode", setOf("automatic", "composer")::contains),
    LOCATION_PERMISSION("locationPermission", setOf("precise", "approximate", "denied")::contains),
    SMS_PERMISSION("smsPermission", PERMISSION_WORDS::contains),
    NOTIFICATION_PERMISSION("notificationPermission", PERMISSION_WORDS::contains),
    ;

    /** [value] if this key accepts it and nothing in it looks personal; otherwise null. */
    fun clean(value: String): String? = value.takeIf { accepts(it) && !DiagnosticsSanitizer.looksPersonal(it) }
}

private val PERMISSION_WORDS = setOf("granted", "denied", "not_declared")

/** Phone makers the app has battery guidance for (`SosSetup.kt`), plus "other". */
private val OEM_FAMILIES = setOf(
    "samsung", "xiaomi", "oppo", "vivo", "realme", "oneplus", "google", "motorola", "nokia",
    "huawei", "honor", "other",
)

/**
 * The second lock behind the lists above, and the check for anything else that is ever
 * about to leave the phone in a report: does this text look like something personal?
 *
 * It errs on the side of dropping: a version such as "2026.10.13.1" is refused because it
 * could as well be a phone number. Nothing here logs or keeps the text it looks at.
 */
object DiagnosticsSanitizer {
    /** Longer than this is free text, whatever it says. */
    const val MAX_LENGTH = 40
    private const val MAX_WORDS = 3
    private const val PHONE_DIGITS = 7
    private const val TOKEN_LENGTH = 20

    // Digits with the separators people put into phone numbers, with or without a "+".
    private val digitRun = Regex("""\+?\d[\d\s().-]*\d""")

    // Two decimal numbers side by side: a latitude and a longitude.
    private val coordinatePair = Regex("""-?\d{1,3}[.,]\d+\s*[,;/ ]\s*-?\d{1,3}[.,]\d+""")

    // One number with four or more decimals is half a coordinate.
    private val preciseDecimal = Regex("""\d{1,3}\.\d{4,}""")
    private val tokenRun = Regex("""[A-Za-z0-9_\-+/=.]{$TOKEN_LENGTH,}""")
    private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    fun looksPersonal(value: String): Boolean = value.length > MAX_LENGTH ||
        value.trim().split(Regex("""\s+""")).size > MAX_WORDS ||
        looksLikePhoneNumber(value) ||
        coordinatePair.containsMatchIn(value) ||
        preciseDecimal.containsMatchIn(value) ||
        looksLikeAddress(value) ||
        looksLikeToken(value)

    private fun looksLikePhoneNumber(value: String): Boolean =
        digitRun.findAll(value).any { run -> run.value.count(Char::isDigit) >= PHONE_DIGITS }

    /** A web address, an email address, or anything with a query in it. */
    private fun looksLikeAddress(value: String): Boolean =
        "://" in value || "www." in value || '@' in value || ('?' in value && '=' in value)

    private fun looksLikeToken(value: String): Boolean = uuid.containsMatchIn(value) ||
        value.startsWith("eyJ") ||
        tokenRun.findAll(value).any { run -> run.value.any(Char::isDigit) && run.value.any(Char::isLetter) }
}
