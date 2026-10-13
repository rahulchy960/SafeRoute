// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import java.time.Duration
import java.time.Instant

/**
 * Everything "Report a problem" may show and share (ADR 0028). The types say what can be
 * in it: versions, the phone's make and model, words for states, and categories of events.
 * There is no field for a place, a contact, a search or an account.
 *
 * @property facts the allowlisted facts ([DiagnosticKey]) as read just now
 * @property readiness the rows of the SOS setup checklist as status words
 * @property toFix how many of those rows need something done
 * @property events the last categories from [DiagnosticsLog], oldest first
 */
data class ProblemReportInput(
    val appVersion: String,
    val buildType: String,
    val make: String,
    val model: String,
    val androidVersion: String,
    val facts: Map<DiagnosticKey, String>,
    val readiness: List<Pair<String, String>>,
    val toFix: Int,
    val events: List<DiagnosticsLog.Entry>,
    val now: Instant,
)

private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** "just now", "5 min ago", "3 h ago": how long ago, never the time of day. */
internal fun relativeTime(at: Instant, now: Instant): String {
    val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_HOUR * HOURS_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        else -> "${minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY)} d ago"
    }
}

/**
 * The text of the report, exactly as the person reads it before sending and exactly as it
 * is shared. It is a list of technical facts for the developer, so its labels are fixed
 * English words, like the event categories themselves; the screen around it is translated.
 *
 * [note] is what the person typed, if anything. It is their own text and is added as it
 * is: the screen asks them not to put names, numbers or places into it.
 *
 * A pure function: it is built in memory when the screen asks and kept nowhere.
 */
fun problemReportText(input: ProblemReportInput, note: String = ""): String = buildString {
    appendLine("SafeRoute problem report")
    appendLine("App: ${input.appVersion} (${input.buildType})")
    appendLine("Phone: ${input.make} ${input.model}")
    appendLine("Android: ${input.androidVersion}")
    for (key in listOf(DiagnosticKey.LOCALE, DiagnosticKey.NETWORK_TYPE)) {
        appendLine("${key.key}: ${input.facts[key] ?: "unknown"}")
    }
    appendLine()
    appendLine("Permissions and SMS:")
    for (key in listOf(
        DiagnosticKey.LOCATION_PERMISSION,
        DiagnosticKey.SMS_PERMISSION,
        DiagnosticKey.NOTIFICATION_PERMISSION,
        DiagnosticKey.SMS_MODE,
    )) {
        appendLine("- ${key.key}: ${input.facts[key] ?: "unknown"}")
    }
    appendLine()
    appendLine("SOS setup: ${if (input.toFix == 0) "ready" else "not ready, ${input.toFix} to fix"}")
    for ((item, status) in input.readiness) appendLine("- $item: $status")
    appendLine()
    if (input.events.isEmpty()) {
        appendLine("Recent events: none since the app was started")
    } else {
        appendLine("Recent events (newest first):")
        for (entry in input.events.asReversed()) {
            appendLine("- ${relativeTime(entry.at, input.now)}: ${entry.category}")
        }
    }
    if (note.isNotBlank()) {
        appendLine()
        appendLine("Note from the tester:")
        appendLine(note.trim())
    }
}.trimEnd()
