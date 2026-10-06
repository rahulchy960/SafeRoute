// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The map as a whole: it makes controllers and draws the map for one. Injected by Hilt, so the
 * app gets the MapLibre one and tests get a fake that loads no native code.
 */
interface MapEngine {
    /**
     * @param scope the owner's scope (a ViewModel's): the controller stops with it.
     * @param initialCamera where to open; null means the launch region's default.
     */
    fun createController(scope: CoroutineScope, initialCamera: CameraState?): MapController

    /** Draws the map for a [controller] made by this engine. */
    @Composable
    fun Map(controller: MapController, modifier: Modifier)
}

/** Whether the phone has a connection that can reach the internet. */
interface NetworkStatus {
    val isOnline: StateFlow<Boolean>
}

/**
 * Asks Android. The callback fires whenever the phone's default network appears, changes or
 * goes away, so the value is always current. Needs ACCESS_NETWORK_STATE, a normal permission
 * that the manifest already has.
 */
@Singleton
class ConnectivityNetworkStatus @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkStatus {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val online = MutableStateFlow(manager.activeNetwork.hasInternet())
    override val isOnline: StateFlow<Boolean> = online.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online.value = true
                }

                override fun onLost(network: Network) {
                    online.value = false
                }
            },
        )
    }

    private fun Network?.hasInternet(): Boolean =
        this?.let(manager::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/** The calls a native map view must receive as its screen comes and goes. */
internal interface MapLifecycleTarget {
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
}

/**
 * Passes the screen's lifecycle on to the map view.
 *
 * A Compose screen has no `onStart()` or `onDestroy()` of its own, but the native map needs
 * them: it starts its renderer in `onStart`, pauses drawing in `onPause`, and frees its
 * graphics memory in `onDestroy`. A missed `onDestroy` leaks the whole map; a missed `onStop`
 * keeps it drawing in the background and draining the battery.
 *
 * Whatever state the screen is in when it disappears, [destroy] walks the view down in order
 * (pause, stop, destroy), each step at most once.
 */
internal class MapLifecycleForwarder(
    private val target: MapLifecycleTarget,
) : LifecycleEventObserver, ComponentCallbacks2 {

    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (destroyed) return
        when (event) {
            Lifecycle.Event.ON_START -> if (!started) {
                started = true
                target.onStart()
            }
            Lifecycle.Event.ON_RESUME -> if (!resumed) {
                resumed = true
                target.onResume()
            }
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    fun destroy() {
        if (destroyed) return
        stop()
        destroyed = true
        target.onDestroy()
    }

    private fun pause() {
        if (!resumed) return
        resumed = false
        target.onPause()
    }

    private fun stop() {
        pause()
        if (!started) return
        started = false
        target.onStop()
    }

    // Android asks apps to give memory back; the map drops tiles it is not showing. The levels
    // are not a simple scale: "UI hidden" (20) sits between "running critical" (15) and
    // "background" (40) and only means the app left the screen, which is not memory pressure.
    override fun onTrimMemory(level: Int) {
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!destroyed && pressure) target.onLowMemory()
    }

    @Deprecated("Android's own callback; newer versions call onTrimMemory instead.")
    override fun onLowMemory() {
        if (!destroyed) target.onLowMemory()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
