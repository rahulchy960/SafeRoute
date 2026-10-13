// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

/**
 * What is reported in place of an error (ADR 0028). An error's message is written by
 * whatever failed and may quote what it was working on: a number that would not parse, a
 * file's path, a line of text. So the message is thrown away. What remains is the name of
 * the error's class, a fixed [ErrorCategory] and the stack trace, which holds only names
 * of classes and methods and line numbers.
 *
 * A "cause" is treated the same way, a few levels deep.
 */
class RedactedError private constructor(
    summary: String,
    cause: RedactedError?,
    frames: Array<StackTraceElement>,
) : Throwable(summary, cause, false, true) {

    init {
        stackTrace = frames
    }

    /** The trace is the original error's, set above; do not record this object's own. */
    override fun fillInStackTrace(): Throwable = this

    companion object {
        private const val MAX_CAUSES = 4

        fun of(error: Throwable, category: ErrorCategory): RedactedError = redact(error, category, MAX_CAUSES)

        /**
         * A typed event as the error that is sent: its fixed category as the text, and the
         * place in the app that reported it as the trace.
         */
        fun ofEvent(event: DiagnosticEvent): RedactedError =
            RedactedError(summary = event.category, cause = null, frames = Throwable().stackTrace)

        private fun redact(error: Throwable, category: ErrorCategory, causesLeft: Int): RedactedError {
            if (error is RedactedError) return error
            val cause = error.cause?.takeIf { it !== error && causesLeft > 0 }
            return RedactedError(
                summary = "${error.javaClass.name} [${category.name}]",
                cause = cause?.let { redact(it, category, causesLeft - 1) },
                frames = error.stackTrace,
            )
        }
    }
}
