// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.saferoute.app.core.map.LatLng
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/*
 * The only file that talks to Android's location services. Everything else in the app sees
 * LocationRepository. Nothing here runs in JVM tests (it needs Google Play services); the
 * decisions live in DefaultLocationRepository, which is tested with fakes.
 */

/**
 * Positions from the Fused Location Provider: Google Play services combines GPS, Wi-Fi and
 * mobile networks and picks the best answer for the battery cost asked for.
 *
 * Precise permission: high accuracy about every 3 seconds, for a dot that moves smoothly while
 * the map is on screen. Approximate permission: balanced power about every 10 seconds; the
 * system rounds those positions anyway. Updates exist only between [start] and [stop].
 */
@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var callback: LocationCallback? = null

    // The repository checks the permission before every start(); a revoked permission between
    // that check and this call is caught below.
    @SuppressLint("MissingPermission")
    override fun start(precise: Boolean, onFix: (RawFix) -> Unit) {
        stop()
        val request = if (precise) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, PRECISE_INTERVAL_MILLIS)
                .setMinUpdateIntervalMillis(PRECISE_MIN_INTERVAL_MILLIS)
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, COARSE_INTERVAL_MILLIS)
        }.build()
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onFix(it.toRawFix()) }
            }
        }
        try {
            // Results arrive on the main thread.
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
            callback = listener
        } catch (_: SecurityException) {
            // The permission went away. The next resume recomputes the permission state.
        }
    }

    override fun stop() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }

    // Same permission reasoning as start(): the repository checked just before.
    @SuppressLint("MissingPermission")
    override fun lastKnown(onResult: (fix: RawFix, ageMillis: Long) -> Unit) {
        try {
            // Answers from what the phone already holds; it is null on a phone that has not
            // located itself since it was switched on. Delivered on the main thread.
            client.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    // Both clocks count from boot and keep running while the phone sleeps.
                    val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                    onResult(location.toRawFix(), ageNanos / NANOS_PER_MILLI)
                }
            }
        } catch (_: SecurityException) {
            // The permission went away. The next resume recomputes the permission state.
        }
    }

    private companion object {
        const val PRECISE_INTERVAL_MILLIS = 3_000L
        const val PRECISE_MIN_INTERVAL_MILLIS = 2_000L
        const val COARSE_INTERVAL_MILLIS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Metres per second. Below walking pace the reported direction is noise. */
private const val MOVING_SPEED = 0.5f

private fun Location.toRawFix() = RawFix(
    position = LatLng(latitude, longitude),
    accuracyMeters = if (hasAccuracy()) accuracy else 0f,
    headingDegrees = if (hasBearing() && hasSpeed() && speed >= MOVING_SPEED) bearing else null,
    isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    },
)

/**
 * The trail of an emergency: its own request to the Fused Location Provider, at the interval
 * the SOS runner asks for. Android delivers positions to it in the background only while the
 * SOS foreground service of type "location" is running.
 */
@Singleton
class FusedTrailLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : TrailLocationSource {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var callback: LocationCallback? = null

    // The SOS trail checks the permission before every start().
    @SuppressLint("MissingPermission")
    override fun start(precise: Boolean, intervalMillis: Long, onFix: (RawFix) -> Unit) {
        stop()
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, intervalMillis).build()
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onFix(it.toRawFix()) }
            }
        }
        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
            callback = listener
        } catch (_: SecurityException) {
            // The permission went away: the emergency goes on without a trail.
        }
    }

    override fun stop() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }

    @SuppressLint("MissingPermission")
    override fun lastKnown(onResult: (fix: RawFix, ageMillis: Long) -> Unit) {
        try {
            client.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                    onResult(location.toRawFix(), ageNanos / 1_000_000L)
                }
            }
        } catch (_: SecurityException) {
            // As above.
        }
    }
}

@Singleton
class AndroidLocationEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationEnvironment {

    override fun granted(): GrantedLocation = when {
        has(Manifest.permission.ACCESS_FINE_LOCATION) -> GrantedLocation.Precise
        has(Manifest.permission.ACCESS_COARSE_LOCATION) -> GrantedLocation.Approximate
        else -> GrantedLocation.None
    }

    override fun isLocationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)
            ?.let(LocationManagerCompat::isLocationEnabled) == true

    override fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** One module, so that tests replace all of it at once (`FakeLocationModule`). */
@Module
@InstallIn(SingletonComponent::class)
interface LocationModule {

    @Binds
    fun bindLocationRepository(repository: DefaultLocationRepository): LocationRepository

    @Binds
    fun bindLocationSource(source: FusedLocationSource): LocationSource

    @Binds
    fun bindTrailLocationSource(source: FusedTrailLocationSource): TrailLocationSource

    @Binds
    fun bindLocationEnvironment(environment: AndroidLocationEnvironment): LocationEnvironment
}
