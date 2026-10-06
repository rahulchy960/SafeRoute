// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

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
        // Overlay colours come from the design system and change with the theme.
        val colors = SafeRouteTheme.colors
        val palette = OverlayPalette(
            location = colors.location.toHex(),
            stale = colors.locationStale.toHex(),
            halo = Color.White.toHex(),
            line = colors.location.toHex(),
        )

        // One map view for as long as this composable is on screen. After a rotation a new one
        // is created and opens at the camera the holder remembered.
        val mapView = remember {
            initMapLibre(context.applicationContext, config)
            MapView(context, mapOptions(context, holder.camera.value)).apply {
                // The saved state is the holder's job (camera only), so no Bundle is passed.
                onCreate(null)
            }
        }

        val renderer = remember(mapView) { MapLibreRenderer(mapView, holder, palette) }
        SideEffect { renderer.setPalette(palette) }

        DisposableEffect(mapView, lifecycle) {
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

private fun Color.toHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

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
    private var palette: OverlayPalette,
) : MapRenderer {

    private var map: MapLibreMap? = null
    private var stopped = false
    private var overlays: List<MapOverlay> = emptyList()

    // A new style (first load, theme change) starts without our layers: add them again.
    private val onStyleLoaded = MapView.OnDidFinishLoadingStyleListener {
        drawOverlays()
        holder.onStyleLoaded()
    }
    private val onFailed = MapView.OnDidFailLoadingMapListener { message ->
        holder.onLoadFailed(message.orEmpty())
    }

    fun start() {
        stopped = false
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

    override fun setOverlays(overlays: List<MapOverlay>) {
        this.overlays = overlays
        drawOverlays()
    }

    fun setPalette(palette: OverlayPalette) {
        if (this.palette == palette) return
        this.palette = palette
        drawOverlays()
    }

    /**
     * All overlays live in one GeoJSON source; four layers each paint one kind of feature
     * (areas, lines, dots, heading arrows), reading colour and opacity from the feature. An
     * update only replaces the source's data.
     */
    private fun drawOverlays() {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return
        val json = overlaysToGeoJson(overlays, palette)
        val source = style.getSourceAs<GeoJsonSource>(OVERLAY_SOURCE)
        if (source != null) {
            source.setGeoJson(json)
            return
        }
        style.addSource(GeoJsonSource(OVERLAY_SOURCE, json))
        style.addImage(HEADING_IMAGE, headingArrow(mapView.context, palette.location))
        val color = Expression.toColor(Expression.get("color"))
        val opacity = Expression.toNumber(Expression.get("opacity"))
        style.addLayer(
            FillLayer("$OVERLAY_SOURCE-fill", OVERLAY_SOURCE).withProperties(
                PropertyFactory.fillColor(color),
                PropertyFactory.fillOpacity(opacity),
            ).apply { setFilter(kindIs(OverlayKind.FILL)) },
        )
        style.addLayer(
            LineLayer("$OVERLAY_SOURCE-line", OVERLAY_SOURCE).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(4f),
            ).apply { setFilter(kindIs(OverlayKind.LINE)) },
        )
        style.addLayer(
            SymbolLayer("$OVERLAY_SOURCE-heading", OVERLAY_SOURCE).withProperties(
                PropertyFactory.iconImage(HEADING_IMAGE),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("heading"))),
                // Turns with the map, so the arrow keeps pointing along the street.
                PropertyFactory.iconRotationAlignment("map"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ).apply { setFilter(kindIs(OverlayKind.HEADING)) },
        )
        style.addLayer(
            CircleLayer("$OVERLAY_SOURCE-dot", OVERLAY_SOURCE).withProperties(
                PropertyFactory.circleRadius(DOT_RADIUS),
                PropertyFactory.circleColor(color),
                PropertyFactory.circleOpacity(opacity),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("stroke"))),
                PropertyFactory.circleStrokeWidth(DOT_STROKE),
            ).apply { setFilter(kindIs(OverlayKind.DOT)) },
        )
    }

    private fun kindIs(kind: String) = Expression.eq(Expression.get("kind"), kind)

    private companion object {
        const val CompassSideMarginPx = 24
        const val OVERLAY_SOURCE = "saferoute-overlays"
        const val HEADING_IMAGE = "saferoute-heading"
        const val DOT_RADIUS = 7f
        const val DOT_STROKE = 2.5f
    }
}

/**
 * A small arrowhead that sits above the dot and shows the direction of travel. Drawn here
 * instead of shipped as a file so that it takes the theme's colour. The picture is taller than
 * the arrow: its centre is the dot, the arrow is in the top part.
 */
private fun headingArrow(context: Context, colorHex: String): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (HEADING_IMAGE_DP * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(colorHex) }
    val center = size / 2f
    val arrow = Path().apply {
        moveTo(center, 0f)
        lineTo(center + 6 * density, 9 * density)
        lineTo(center - 6 * density, 9 * density)
        close()
    }
    Canvas(bitmap).drawPath(arrow, paint)
    return bitmap
}

private const val HEADING_IMAGE_DP = 44

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
