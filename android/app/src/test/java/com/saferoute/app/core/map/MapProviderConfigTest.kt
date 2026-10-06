// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key rule, the style addresses, and the promise that the key is never printed. */
class MapProviderConfigTest {

    private val config = MapProviderConfig(MapKey(FAKE_MAP_KEY))

    @Test
    fun `a url-safe key of sensible length is accepted`() {
        assertNull(mapKeyProblem(FAKE_MAP_KEY))
        assertNull(mapKeyProblem("abcDEF12"))
        assertNull(mapKeyProblem("a".repeat(64)))
    }

    @Test
    fun `empty, short, long and unsafe keys are refused without echoing them`() {
        val bad = listOf("", "short", "a".repeat(65), "has space 1234", "quote\"1234567", "a/b?c=d&e12")
        bad.forEach { key ->
            val problem = mapKeyProblem(key)
            assertNotNull("accepted: ${key.length} characters", problem)
            if (key.length > 3) assertFalse(problem!!.contains(key))
            val thrown = assertThrows(IllegalArgumentException::class.java) { MapKey(key) }
            if (key.length > 3) assertFalse(thrown.message.orEmpty().contains(key))
        }
    }

    @Test
    fun `a build without a usable key gives a config that is not configured`() {
        // What the build passes for the placeholder and for CI's dummy key.
        val missing = MapProviderConfig.fromBuild(key = "map-key-not-configured", configured = false)

        assertFalse(missing.isConfigured)
        assertNull(missing.styleUrl(MapStyleVariant.Light))
        assertTrue(MapProviderConfig.fromBuild(FAKE_MAP_KEY, configured = true).isConfigured)
    }

    @Test
    fun `the style address is built from the variant and the key`() {
        assertEquals(
            "https://api.maptiler.com/maps/streets-v4/style.json?key=$FAKE_MAP_KEY",
            config.styleUrl(MapStyleVariant.Light)?.reveal(),
        )
        assertEquals(
            "https://api.maptiler.com/maps/streets-v4-dark/style.json?key=$FAKE_MAP_KEY",
            config.styleUrl(MapStyleVariant.Dark)?.reveal(),
        )
    }

    @Test
    fun `nothing prints the key`() {
        val printed = listOf(
            MapKey(FAKE_MAP_KEY).toString(),
            config.toString(),
            config.styleUrl(MapStyleVariant.Light).toString(),
            "$config ${config.key} ${config.styleUrl(MapStyleVariant.Dark)}",
        )
        printed.forEach { assertFalse(it, it.contains(FAKE_MAP_KEY)) }
    }

    @Test
    fun `the key is removed from library messages`() {
        val url = config.styleUrl(MapStyleVariant.Light)!!.reveal()
        val messages = listOf(
            "loading style failed: HTTP status code 403 for $url",
            "Failed to load tile https://api.maptiler.com/tiles/v3/1/2/3.pbf?key=$FAKE_MAP_KEY&x=1",
            "key $FAKE_MAP_KEY was refused",
        )
        messages.forEach { message ->
            val cleaned = config.redact(message)
            assertFalse(cleaned, cleaned.contains(FAKE_MAP_KEY))
            assertTrue(cleaned, cleaned.contains("<map-key>"))
        }
        // The rest of the message survives, so the log line is still useful.
        assertTrue(config.redact(messages[1]).endsWith("?key=<map-key>&x=1"))
    }

    @Test
    fun `a key parameter is removed even when the config has no key`() {
        val cleaned = MapProviderConfig(null).redact("GET https://host.invalid/a?key=someOtherKey99")
        assertEquals("GET https://host.invalid/a?key=<map-key>", cleaned)
    }

    @Test
    fun `positions do not print their coordinates`() {
        val camera = CameraState(LatLng(12.345678, 98.765432), zoom = 14.0)
        listOf(camera.toString(), camera.target.toString()).forEach {
            assertFalse(it, it.contains("12.3") || it.contains("98.7"))
        }
    }

    @Test
    fun `the launch region default is a city-level view inside the zoom limits`() {
        val zoom = RegionDefaults.camera.zoom
        assertTrue(zoom in RegionDefaults.MIN_ZOOM..RegionDefaults.MAX_ZOOM)
        assertEquals(0.0, RegionDefaults.camera.bearing, 0.0)
        assertTrue(MapProviderConfig.AMBIENT_CACHE_BYTES in (50L shl 20)..(100L shl 20))
    }
}
