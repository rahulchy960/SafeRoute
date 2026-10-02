// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emergency red means one thing only (ADR 0008). This test reads the source files and
 * fails if a `sos` colour token is mentioned anywhere except the places allowed to use it.
 *
 * To let a new emergency surface use the colour, add its file to [allowedFiles] in the same
 * pull request, so the reviewer sees the exception.
 */
class SosColourUsageTest {

    /** Files that may use the sos tokens. The theme package only defines them. */
    private val allowedFiles = setOf("EmergencyButton.kt")
    private val definitionPackage = "core/designsystem/theme"

    private val sosToken = Regex("""\b(sos|onSos|sosContainer|onSosContainer)\b""")

    @Test
    fun `sos colour tokens are used only by emergency components`() {
        val sourceRoot = File("src/main/java")
        assertTrue("Not found: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains(definitionPackage) }
            .filter { file -> file.useLines { lines -> lines.any(sosToken::containsMatchIn) } }
            .map { it.name }
            .filterNot { it in allowedFiles }
            .toList()

        assertEquals("Files using the sos colour without being allowed to", emptyList<String>(), offenders)
    }

    @Test
    fun `the allowed emergency components do use the sos colour`() {
        // Guards against the allow-list going stale after a rename.
        val names = File("src/main/java").walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertTrue("Allowed files no longer exist: ${allowedFiles - names}", names.containsAll(allowedFiles))
    }
}
