// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's state machine with a fake map view: Loading, Ready, Error, Offline, NotConfigured
 * and RateLimited, and what moves between them. Time is virtual, so the 20-second limit is
 * tested in milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapStateHolderTest {

    private val network = FakeNetworkStatus()
    private val renderer = FakeMapRenderer()
    private val configured = MapProviderConfig(MapKey(FAKE_MAP_KEY))

    private fun TestScope.holder(
        config: MapProviderConfig = configured,
        camera: CameraState = RegionDefaults.camera,
    ) = MapStateHolder(config, network, backgroundScope, camera).also { runCurrent() }

    private val forbidden = "loading style failed: HTTP status code 403"
    private val timeout = "loading style failed: timeout"

    @Test
    fun `without a key the map is not configured and nothing is requested`() = runTest {
        val holder = holder(MapProviderConfig(null))

        holder.attach(renderer)
        holder.retry()
        holder.setStyleVariant(MapStyleVariant.Dark)
        holder.onLoadFailed(timeout)

        assertEquals(MapLoadState.NotConfigured, holder.loadState.value)
        assertTrue(renderer.styleRequests.isEmpty())
    }

    @Test
    fun `attaching a map view loads the light style and a loaded style is ready`() = runTest {
        val holder = holder()
        assertEquals(MapLoadState.Loading, holder.loadState.value)

        holder.attach(renderer)
        assertEquals(MapLoadState.Loading, holder.loadState.value)
        assertTrue(renderer.styleRequests.single().reveal().contains("/streets-v4/"))

        holder.onStyleLoaded()
        assertEquals(MapLoadState.Ready, holder.loadState.value)

        // Ready stays ready: the watchdog was cancelled.
        advanceTimeBy(MapStateHolder.LOAD_TIMEOUT_MILLIS * 2)
        assertEquals(MapLoadState.Ready, holder.loadState.value)
    }

    @Test
    fun `the theme picks the style and a change reloads it without a crash`() = runTest {
        val holder = holder()
        holder.setStyleVariant(MapStyleVariant.Dark)
        holder.attach(renderer)
        holder.onStyleLoaded()

        holder.setStyleVariant(MapStyleVariant.Light)
        assertEquals(MapLoadState.Loading, holder.loadState.value)
        holder.onStyleLoaded()
        holder.setStyleVariant(MapStyleVariant.Light)

        val styles = renderer.styleRequests.map { it.reveal().substringAfter("/maps/").substringBefore("/") }
        assertEquals(listOf("streets-v4-dark", "streets-v4"), styles)
        assertEquals(MapLoadState.Ready, holder.loadState.value)
    }

    @Test
    fun `a failed load while online is a retryable error and retry loads again`() = runTest {
        val holder = holder()
        holder.attach(renderer)

        holder.onLoadFailed(timeout)
        assertEquals(MapLoadState.Error, holder.loadState.value)

        holder.retry()
        assertEquals(MapLoadState.Loading, holder.loadState.value)
        assertEquals(2, renderer.styleRequests.size)

        holder.onStyleLoaded()
        assertEquals(MapLoadState.Ready, holder.loadState.value)
    }

    @Test
    fun `a refused key or a used-up quota is temporarily unavailable, before or after loading`() = runTest {
        listOf("401", "403", "429").forEach { code ->
            val holder = holder()
            holder.attach(FakeMapRenderer())
            holder.onLoadFailed("loading style failed: HTTP status code $code")
            assertEquals(code, MapLoadState.RateLimited, holder.loadState.value)
        }

        val loaded = holder()
        loaded.attach(renderer)
        loaded.onStyleLoaded()
        loaded.onLoadFailed("Failed to load tile: HTTP status code 429")
        assertEquals(MapLoadState.RateLimited, loaded.loadState.value)
    }

    @Test
    fun `one failed tile does not replace a working map with an error`() = runTest {
        val holder = holder()
        holder.attach(renderer)
        holder.onStyleLoaded()

        holder.onLoadFailed("Failed to load tile: HTTP status code 404")
        holder.onLoadFailed(timeout)

        assertEquals(MapLoadState.Ready, holder.loadState.value)
    }

    @Test
    fun `offline with nothing cached says offline after a short wait`() = runTest {
        network.online.value = false
        val holder = holder()
        holder.attach(renderer)

        // The library waits for a connection instead of failing, so no failure arrives.
        advanceTimeBy(MapStateHolder.OFFLINE_GRACE_MILLIS - 1)
        assertEquals(MapLoadState.Loading, holder.loadState.value)
        advanceTimeBy(2)
        assertEquals(MapLoadState.Offline, holder.loadState.value)
    }

    @Test
    fun `offline with a cached style keeps working silently`() = runTest {
        network.online.value = false
        val holder = holder()
        holder.attach(renderer)

        holder.onStyleLoaded()
        advanceTimeBy(MapStateHolder.LOAD_TIMEOUT_MILLIS)

        assertEquals(MapLoadState.Ready, holder.loadState.value)
    }

    @Test
    fun `a failure while offline is offline, and the connection coming back recovers by itself`() = runTest {
        val holder = holder()
        holder.attach(renderer)
        network.online.value = false
        runCurrent()

        holder.onLoadFailed(timeout)
        assertEquals(MapLoadState.Offline, holder.loadState.value)

        network.online.value = true
        runCurrent()
        assertEquals(MapLoadState.Loading, holder.loadState.value)
        assertEquals(2, renderer.styleRequests.size)

        // With the style already drawn there is nothing to reload: tiles resume on their own.
        holder.onStyleLoaded()
        network.online.value = false
        runCurrent()
        holder.onLoadFailed(timeout)
        assertEquals(MapLoadState.Offline, holder.loadState.value)
        network.online.value = true
        runCurrent()
        assertEquals(MapLoadState.Ready, holder.loadState.value)
        assertEquals(2, renderer.styleRequests.size)
    }

    @Test
    fun `loading never lasts for ever`() = runTest {
        val holder = holder()
        holder.attach(renderer)

        advanceTimeBy(MapStateHolder.LOAD_TIMEOUT_MILLIS - 1)
        assertEquals(MapLoadState.Loading, holder.loadState.value)
        advanceTimeBy(2)
        assertEquals(MapLoadState.Error, holder.loadState.value)
    }

    @Test
    fun `the camera and padding survive a new map view`() = runTest {
        val saved = CameraState(LatLng(10.0, 20.0), zoom = 15.0, bearing = 90.0)
        val holder = holder(camera = saved)
        assertEquals(saved, holder.camera.value)

        holder.setPadding(MapPadding(top = 100, bottom = 400))
        holder.attach(renderer)
        holder.onStyleLoaded()
        val moved = CameraState(LatLng(11.0, 21.0), zoom = 16.0, bearing = 45.0)
        holder.onCameraIdle(moved)

        // Rotation: the old view goes, a new one attaches.
        holder.detach(renderer)
        val second = FakeMapRenderer()
        holder.attach(second)

        assertEquals(moved, holder.camera.value)
        assertEquals(listOf(MapPadding(top = 100, bottom = 400)), second.paddings)
        assertEquals(1, second.styleRequests.size)
        assertEquals(MapLoadState.Loading, holder.loadState.value)
    }

    @Test
    fun `padding and camera requests reach the map view, unchanged padding only once`() = runTest {
        val holder = holder()
        holder.attach(renderer)
        val padding = MapPadding(bottom = 300)

        holder.setPadding(padding)
        holder.setPadding(padding)
        val north = holder.camera.value.copy(bearing = 0.0)
        holder.moveCamera(north, animate = true)

        assertEquals(listOf(MapPadding(), padding), renderer.paddings)
        assertEquals(listOf(north to true), renderer.cameraMoves)
    }

    @Test
    fun `a detached view no longer times out the state`() = runTest {
        val holder = holder()
        holder.attach(renderer)
        holder.detach(renderer)

        advanceTimeBy(MapStateHolder.LOAD_TIMEOUT_MILLIS * 2)

        assertEquals(MapLoadState.Loading, holder.loadState.value)
    }

    @Test
    fun `failure texts are classified by status code only`() {
        assertEquals(MapFailure.Refused, classifyMapFailure("x HTTP status code 403 y"))
        assertEquals(MapFailure.Other, classifyMapFailure("HTTP status code 404"))
        assertEquals(MapFailure.Other, classifyMapFailure("HTTP status code 500"))
        assertEquals(MapFailure.Other, classifyMapFailure(""))
        assertEquals(MapFailure.Other, classifyMapFailure("403"))
    }
}
