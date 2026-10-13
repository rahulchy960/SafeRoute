// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

/**
 * Everything the app may report as a "non-fatal" problem (ADR 0028). The list is closed on
 * purpose: an event is a name and, for two of them, one value of an enum. There is no
 * field for text, a number, a place or a person, so none can be reported by mistake.
 *
 * Not on the list, and never to be added: an SOS that started or went well, a contact, a
 * search, a route request.
 */
sealed interface DiagnosticEvent {
    /** The fixed word shown in "Report a problem" and sent as the report's name. */
    val category: String

    /** An SOS text could not be handed to the phone. [result] says why, as a fixed word. */
    data class SmsSendFailed(val result: SmsFailure) : DiagnosticEvent {
        override val category = "SMS_SEND_FAILED(${result.word})"
    }

    /** The SOS service was found stopped although an SOS was running. */
    data object ServiceKilled : DiagnosticEvent {
        override val category = "SERVICE_KILLED"
    }

    /** The app found an unfinished SOS at start and showed the recovery screen. */
    data object RecoveryShown : DiagnosticEvent {
        override val category = "RECOVERY_SHOWN"
    }

    /** No position could be had when one was wanted. Never the position itself. */
    data object LocationUnavailable : DiagnosticEvent {
        override val category = "LOCATION_UNAVAILABLE"
    }

    /** A request to the SafeRoute API failed. [kind] is the kind of failure only. */
    data class ApiError(val kind: ApiErrorKind) : DiagnosticEvent {
        override val category = "API_ERROR(${kind.word})"
    }

    /** The retries of an unsent SOS text ended without it being sent. */
    data object AlertsGivenUp : DiagnosticEvent {
        override val category = "ALERTS_GIVEN_UP"
    }
}

/** Why the phone did not take an SOS text. The words are the ones `SosSms.kt` records. */
enum class SmsFailure(val word: String) {
    REJECTED("rejected"),
    RADIO_OFF("radio_off"),
    NO_SERVICE("no_service"),
    LIMIT("limit"),
    SIM_ABSENT("sim_absent"),
    GENERIC("generic"),
    NO_PERMISSION("no_permission"),
    NO_SMS_SERVICE("no_sms_service"),
    NO_TELEPHONY("no_telephony"),
    EMPTY("empty"),
    TIMEOUT("timeout"),
    OTHER("other"),
    ;

    companion object {
        /** An unknown word becomes [OTHER]: the word itself is never passed on. */
        fun fromWord(word: String?): SmsFailure = entries.firstOrNull { it.word == word } ?: OTHER
    }
}

/** The kind of an API failure: no status text, no address, no request id. */
enum class ApiErrorKind(val word: String) {
    NO_CONNECTION("no_connection"),
    UNAUTHORIZED("unauthorized"),
    RATE_LIMITED("rate_limited"),
    CLIENT_ERROR("client_error"),
    UNAVAILABLE("unavailable"),
    SERVER_ERROR("server_error"),
    UNEXPECTED("unexpected"),
}

/** Where an error was caught. It replaces the error's own message in a report. */
enum class ErrorCategory {
    /** Nothing caught it: the app is about to close. */
    UNCAUGHT,
    STORAGE,
    BACKGROUND_WORK,

    /** The "Test crash" and "Test non-fatal" buttons of a debug build. */
    TEST,
}
