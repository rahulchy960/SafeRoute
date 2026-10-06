// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import android.content.Context
import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.log.Logger
import org.maplibre.android.log.LoggerDefinition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

/*
 * The ONLY file in the app that imports the map library (MapLibreBoundaryTest fails the build
 * otherwise). Everything outside sees MapEngine, MapController and the plain types in
 * MapTypes.kt. Native code cannot run in JVM unit tests, so this file is kept thin: decisions
 * live in MapStateHolder and MapLifecycleForwarder, which are tested.
 *
 * Networking: MapLibre downloads styles and tiles with an HTTP client of its own. The app's
 * API client (core/network, with the sign-in token) is never handed to it:
 * HttpRequestUtil.setOkHttpClient is deliberately not called anywhere.
 */

/** [MapEngine] backed by MapLibre Native, showing MapTiler styles. */
@Singleton
class MapLibreEngine @Inject constructor(
    private val config: MapProviderConfig,
    private val network: NetworkStatus,
) : MapEngine {

    override fun createController(scope: CoroutineScope, initialCamera: CameraState?): MapController =
        MapStateHolder(config, network, scope, initialCamera ?: RegionDefaults.camera)

    @Composable
    override fun Map(controller: MapController, modifier: Modifier) {
        val holder = controller as? MapStateHolder
            ?: error("This controller was not made by MapLibreEngine.createController().")
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        // One map view for as long as this composable is on screen. After a rotation a new one
        // is created and opens at the camera the holder remembered.
        val mapView = remember {
            initMapLibre(context.applicationContext, config)
            MapView(context, mapOptions(context, holder.camera.value)).apply {
                // The saved state is the holder's job (camera only), so no Bundle is passed.
                onCreate(null)
            }
        }

        DisposableEffect(mapView, lifecycle) {
            val renderer = MapLibreRenderer(mapView, holder)
            val forwarder = MapLifecycleForwarder(mapView.asLifecycleTarget())
            // addObserver replays the events the screen has already been through (create,
            // start, resume), so the map catches up with a screen that is already showing.
            lifecycle.addObserver(forwarder)
            context.applicationContext.registerComponentCallbacks(forwarder)
            renderer.start()
            onDispose {
                renderer.stop()
                lifecycle.removeObserver(forwarder)
                context.applicationContext.unregisterComponentCallbacks(forwarder)
                forwarder.destroy()
            }
        }

        AndroidView(factory = { mapView }, modifier = modifier)
    }
}

private var mapLibreReady = false

/** Process-wide setup, once, before the first map view. */
private fun initMapLibre(appContext: Context, config: MapProviderConfig) {
    if (mapLibreReady) return
    mapLibreReady = true
    MapLibre.getInstance(appContext)
    // The style address carries the key as `?key=...`. The library must not print addresses,
    // and whatever it does log goes through a filter that removes the key.
    HttpRequestUtil.setLogEnabled(false)
    HttpRequestUtil.setPrintRequestUrlOnFailure(false)
    Logger.setLoggerDefinition(RedactingLogger(config))
    OfflineManager.getInstance(appContext)
        .setMaximumAmbientCacheSize(MapProviderConfig.AMBIENT_CACHE_BYTES, null)
}

private fun mapOptions(context: Context, camera: CameraState): MapLibreMapOptions =
    MapLibreMapOptions.createFromAttributes(context)
        .camera(camera.toCameraPosition())
        .minZoomPreference(RegionDefaults.MIN_ZOOM)
        .maxZoomPreference(RegionDefaults.MAX_ZOOM)
        // The credit line is drawn by the screen (always visible, translated, 48 dp), so the
        // library's own logo and "i" button are off. ADR 0015, "Attribution".
        .logoEnabled(false)
        .attributionEnabled(false)
        // The compass appears only while the map is turned; tapping it turns north back up.
        .compassEnabled(true)
        .compassFadesWhenFacingNorth(true)
        .compassGravity(Gravity.TOP or Gravity.END)
        // A flat map: no 3D tilt in the MVP.
        .tiltGesturesEnabled(false)

private fun CameraState.toCameraPosition(): CameraPosition = CameraPosition.Builder()
    .target(org.maplibre.android.geometry.LatLng(target.latitude, target.longitude))
    .zoom(zoom)
    .bearing(bearing)
    .build()

private fun CameraPosition.toCameraState(): CameraState? = target?.let {
    CameraState(LatLng(it.latitude, it.longitude), zoom, bearing)
}

private fun MapView.asLifecycleTarget() = object : MapLifecycleTarget {
    override fun onStart() = this@asLifecycleTarget.onStart()
    override fun onResume() = this@asLifecycleTarget.onResume()
    override fun onPause() = this@asLifecycleTarget.onPause()
    override fun onStop() = this@asLifecycleTarget.onStop()
    override fun onDestroy() = this@asLifecycleTarget.onDestroy()
    override fun onLowMemory() = this@asLifecycleTarget.onLowMemory()
}

/** Connects one map view to the [MapStateHolder]. */
private class MapLibreRenderer(
    private val mapView: MapView,
    private val holder: MapStateHolder,
) : MapRenderer {

    private var map: MapLibreMap? = null
    private var stopped = false

    private val onStyleLoaded = MapView.OnDidFinishLoadingStyleListener { holder.onStyleLoaded() }
    private val onFailed = MapView.OnDidFailLoadingMapListener { message ->
        holder.onLoadFailed(message.orEmpty())
    }

    fun start() {
        mapView.addOnDidFinishLoadingStyleListener(onStyleLoaded)
        mapView.addOnDidFailLoadingMapListener(onFailed)
        // The map object arrives a moment later, once the native side is up.
        mapView.getMapAsync { ready ->
            if (stopped) return@getMapAsync
            map = ready
            ready.addOnCameraIdleListener {
                ready.cameraPosition.toCameraState()?.let(holder::onCameraIdle)
            }
            holder.attach(this)
        }
    }

    fun stop() {
        stopped = true
        holder.detach(this)
        mapView.removeOnDidFinishLoadingStyleListener(onStyleLoaded)
        mapView.removeOnDidFailLoadingMapListener(onFailed)
        map = null
    }

    override fun loadStyle(url: MapStyleUrl) {
        map?.setStyle(Style.Builder().fromUri(url.reveal()))
    }

    override fun setPadding(padding: MapPadding) {
        val map = map ?: return
        // Keeps the map's focus point in the part of the screen that is not covered.
        map.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        map.uiSettings.setCompassMargins(0, padding.top, padding.right + CompassSideMarginPx, 0)
    }

    override fun moveCamera(camera: CameraState, animate: Boolean) {
        val update = CameraUpdateFactory.newCameraPosition(camera.toCameraPosition())
        if (animate) map?.animateCamera(update) else map?.moveCamera(update)
    }

    // Drawn from P010b on (the current-location marker is the first overlay).
    override fun setOverlays(overlays: List<MapOverlay>) = Unit

    private companion object {
        const val CompassSideMarginPx = 24
    }
}

/**
 * MapLibre's log lines, native ones included, pass through here. Only warnings and errors are
 * kept, each with the key removed; exceptions are reduced to their class name because their
 * messages can contain the request address.
 */
private class RedactingLogger(private val config: MapProviderConfig) : LoggerDefinition {
    override fun v(tag: String, msg: String) = Unit
    override fun v(tag: String, msg: String, tr: Throwable) = Unit
    override fun d(tag: String, msg: String) = Unit
    override fun d(tag: String, msg: String, tr: Throwable) = Unit
    override fun i(tag: String, msg: String) = Unit
    override fun i(tag: String, msg: String, tr: Throwable) = Unit

    override fun w(tag: String, msg: String) {
        Log.w(tag, config.redact(msg))
    }

    override fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, "${config.redact(msg)} (${tr.javaClass.simpleName})")
    }

    override fun e(tag: String, msg: String) {
        Log.e(tag, config.redact(msg))
    }

    override fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, "${config.redact(msg)} (${tr.javaClass.simpleName})")
    }
}
