// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The thing that actually draws: the map view of whichever library is in use. [MapStateHolder]
 * tells it what to do; it reports back through the holder's `on…` functions.
 */
internal interface MapRenderer {
    fun loadStyle(url: MapStyleUrl)
    fun setPadding(padding: MapPadding)
    fun moveCamera(camera: CameraState, animate: Boolean)
    fun setOverlays(overlays: List<MapOverlay>)
}

/** Why a load failed, as far as the app cares. */
internal enum class MapFailure {
    /** The provider said no: wrong or restricted key (401, 403) or quota used up (429). */
    Refused,
    Other,
}

private val httpStatusPattern = Regex("""HTTP status code (\d{3})""")

/**
 * Reads the failure text the map library reports. MapLibre Native words a refused request as
 * "... HTTP status code 403". Anything else (timeouts, DNS, a broken style) is [MapFailure.Other].
 */
internal fun classifyMapFailure(message: String): MapFailure =
    when (httpStatusPattern.find(message)?.groupValues?.get(1)) {
        "401", "403", "429" -> MapFailure.Refused
        else -> MapFailure.Other
    }

/**
 * The map's state, kept apart from the map view.
 *
 * The view is destroyed and rebuilt whenever the screen rotates; this object is held by the
 * screen's ViewModel and survives. A new view attaches itself as the [MapRenderer], is told the
 * style, padding and camera, and carries on where the old one stopped. Because all decisions
 * are made here in plain Kotlin, they are unit-tested without loading the native map library.
 *
 * Call everything on the main thread (the map library reports on it, and so does the UI).
 *
 * @param scope ends with the owner (the ViewModel's scope); used for the load watchdog and for
 * listening to the network.
 */
internal class MapStateHolder(
    private val config: MapProviderConfig,
    private val network: NetworkStatus,
    private val scope: CoroutineScope,
    initialCamera: CameraState = RegionDefaults.camera,
) : MapController {

    private val _loadState = MutableStateFlow(
        if (config.isConfigured) MapLoadState.Loading else MapLoadState.NotConfigured,
    )
    override val loadState: StateFlow<MapLoadState> = _loadState.asStateFlow()

    private val _camera = MutableStateFlow(initialCamera)
    override val camera: StateFlow<CameraState> = _camera.asStateFlow()

    private var renderer: MapRenderer? = null
    private var variant = MapStyleVariant.Light
    private var padding = MapPadding()
    private var overlays: List<MapOverlay> = emptyList()
    private var styleLoaded = false
    private var watchdog: Job? = null

    init {
        // Coming back online ends the offline state by itself: nobody should have to tap.
        scope.launch {
            network.isOnline.collect { online ->
                if (online && _loadState.value == MapLoadState.Offline) {
                    if (styleLoaded) _loadState.value = MapLoadState.Ready else load()
                }
            }
        }
    }

    /** A map view is ready to draw. */
    fun attach(renderer: MapRenderer) {
        this.renderer = renderer
        renderer.setPadding(padding)
        renderer.setOverlays(overlays)
        load()
    }

    /** The map view is going away (rotation, leaving the screen). */
    fun detach(renderer: MapRenderer) {
        if (this.renderer !== renderer) return
        this.renderer = null
        styleLoaded = false
        watchdog?.cancel()
    }

    fun onStyleLoaded() {
        styleLoaded = true
        watchdog?.cancel()
        _loadState.value = MapLoadState.Ready
    }

    /**
     * The library reported a failure. It reports failed tiles as well as a failed style, so
     * once the map is drawn only a refusal or being offline changes the state: one missing
     * tile must not replace a working map with an error.
     *
     * @param message the library's text. It can contain the request address, key included, so
     * it is used to classify the failure and then dropped: never stored, shown or logged.
     */
    fun onLoadFailed(message: String) {
        if (!config.isConfigured) return
        val next = when {
            classifyMapFailure(message) == MapFailure.Refused -> MapLoadState.RateLimited
            !network.isOnline.value -> MapLoadState.Offline
            styleLoaded -> return
            else -> MapLoadState.Error
        }
        watchdog?.cancel()
        _loadState.value = next
    }

    /** The map stopped moving. */
    fun onCameraIdle(camera: CameraState) {
        _camera.value = camera
    }

    override fun setPadding(padding: MapPadding) {
        if (this.padding == padding) return
        this.padding = padding
        renderer?.setPadding(padding)
    }

    override fun setStyleVariant(variant: MapStyleVariant) {
        if (this.variant == variant) return
        this.variant = variant
        if (renderer != null) load()
    }

    override fun retry() = load()

    override fun moveCamera(camera: CameraState, animate: Boolean) {
        _camera.value = camera
        renderer?.moveCamera(camera, animate)
    }

    override fun setOverlays(overlays: List<MapOverlay>) {
        this.overlays = overlays
        renderer?.setOverlays(overlays)
    }

    private fun load() {
        val url = config.styleUrl(variant) ?: return
        val renderer = renderer ?: return
        styleLoaded = false
        _loadState.value = MapLoadState.Loading
        renderer.loadStyle(url)

        // "Loading" must always end. Without a connection the library does not fail: it waits
        // for one. A style that is in the cache arrives within moments, so if nothing has
        // arrived shortly and the phone is offline, say so. Otherwise give up after the limit.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(OFFLINE_GRACE_MILLIS)
            if (!network.isOnline.value) {
                _loadState.value = MapLoadState.Offline
                return@launch
            }
            delay(LOAD_TIMEOUT_MILLIS - OFFLINE_GRACE_MILLIS)
            _loadState.value =
                if (network.isOnline.value) MapLoadState.Error else MapLoadState.Offline
        }
    }

    companion object {
        const val OFFLINE_GRACE_MILLIS = 3_000L
        const val LOAD_TIMEOUT_MILLIS = 20_000L
    }
}
