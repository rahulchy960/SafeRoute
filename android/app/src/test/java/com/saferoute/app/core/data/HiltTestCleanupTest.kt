// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.core.di.ApplicationScope
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a Hilt test gives back when it ends (P014e): the database is closed and the
 * app-lifetime scope is stopped, and no Hilt test can forget the rule that does it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class HiltTestCleanupTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 0)
    val cleanup = HiltTestCleanup()

    @Inject lateinit var database: SafeRouteDatabase

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun `a test starts with no database left open by the test before it`() {
        // Only this test's own database, however many Hilt tests ran before in this JVM.
        database.openHelper.writableDatabase
        assertEquals(1, TestResources.openDatabases())
    }

    @Test
    fun `releasing stops the app-lifetime scope and closes the database`() {
        database.openHelper.writableDatabase
        assertTrue(database.isOpen)
        assertTrue(scope.isActive)

        TestResources.release()

        assertFalse(scope.isActive)
        assertFalse(database.isOpen)
        assertEquals(0, TestResources.openDatabases())
    }

    @Test
    fun `every Hilt test declares the clean-up rule`() {
        val missing = listOf("src/test", "src/testDebug")
            .flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                val text = file.readText()
                Regex("""= HiltAndroidRule\(this\)""").findAll(text).count() !=
                    Regex("""= HiltTestCleanup\(\)""").findAll(text).count()
            }
            .map { it.name }

        assertEquals("a Hilt rule without the clean-up rule next to it", emptyList<String>(), missing)
    }

    @Test
    fun `no test sleeps, and only the real sign-in test polls with a time limit`() {
        // Written in two parts so that this file does not find itself.
        val sleep = "Thread." + "sleep("
        val poll = ".wait" + "Until("
        val sources = listOf("src/test", "src/testDebug")
            .flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

        assertEquals(emptyList<String>(), sources.filter { sleep in it.readText() }.map { it.name })
        // SignInToHomeTest talks real HTTP to a local server with the real session code:
        // those threads are not counted by BackgroundWork, so it keeps its limit.
        assertEquals(listOf("SignInToHomeTest.kt"), sources.filter { poll in it.readText() }.map { it.name })
    }
}
