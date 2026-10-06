// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.saferoute.app.core.map.di.MapModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Stand-ins for the map in JVM tests. The real map is native code (a .so file built for phone
 * processors) and cannot be loaded here, and tests must never contact the tile provider.
 */

/** Obviously fake; long enough to pass the key format rule. */
const val FAKE_MAP_KEY = "FAKE-map-key-0000"

class FakeNetworkStatus(online: Boolean = true) : NetworkStatus {
    val online = MutableStateFlow(online)
    override val isOnline: StateFlow<Boolean> = this.online
}

/** Records what the state holder asks the map view to do. */
internal class FakeMapRenderer : MapRenderer {
    val styleRequests = mutableListOf<MapStyleUrl>()
    val paddings = mutableListOf<MapPadding>()
    val cameraMoves = mutableListOf<Pair<CameraState, Boolean>>()
    var lastOverlays: List<MapOverlay> = emptyList()

    override fun loadStyle(url: MapStyleUrl) {
        styleRequests += url
    }

    override fun setPadding(padding: MapPadding) {
        paddings += padding
    }

    override fun moveCamera(camera: CameraState, animate: Boolean) {
        cameraMoves += camera to animate
    }

    override fun setOverlays(overlays: List<MapOverlay>) {
        lastOverlays = overlays
    }
}

/** A controller a UI test can put into any state. */
class FakeMapController(initialCamera: CameraState) : MapController {
    override val loadState = MutableStateFlow<MapLoadState>(MapLoadState.Ready)
    override val camera = MutableStateFlow(initialCamera)
    val paddings = mutableListOf<MapPadding>()
    val variants = mutableListOf<MapStyleVariant>()
    var retries = 0

    override fun setPadding(padding: MapPadding) {
        paddings += padding
    }

    override fun setStyleVariant(variant: MapStyleVariant) {
        variants += variant
    }

    override fun retry() {
        retries++
    }

    override fun moveCamera(camera: CameraState, animate: Boolean) {
        this.camera.value = camera
    }

    override fun setOverlays(overlays: List<MapOverlay>) = Unit
}

class FakeMapEngine : MapEngine {
    val controllers = mutableListOf<FakeMapController>()
    val initialCameras = mutableListOf<CameraState?>()
    var mapsComposed = 0

    val controller: FakeMapController get() = controllers.last()

    override fun createController(scope: CoroutineScope, initialCamera: CameraState?): MapController {
        initialCameras += initialCamera
        return FakeMapController(initialCamera ?: RegionDefaults.camera).also { controllers += it }
    }

    @Composable
    override fun Map(controller: MapController, modifier: Modifier) {
        mapsComposed++
        Box(modifier.testTag(MAP_TAG))
    }

    companion object {
        const val MAP_TAG = "fake-map"
    }
}

/**
 * Replaces [MapModule] in every Hilt test. `@TestInstallIn` applies to the whole test source
 * set, so a test that starts `MainActivity` gets the fake map without asking for it.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [MapModule::class])
object FakeMapModule {

    @Provides
    @Singleton
    fun provideFakeMapEngine(): FakeMapEngine = FakeMapEngine()

    @Provides
    fun provideMapEngine(engine: FakeMapEngine): MapEngine = engine

    @Provides
    @Singleton
    fun provideNetworkStatus(): NetworkStatus = FakeNetworkStatus()

    @Provides
    @Singleton
    fun provideMapProviderConfig(): MapProviderConfig = MapProviderConfig(MapKey(FAKE_MAP_KEY))
}
