// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

/*
 * Everything that is specific to the tile provider (MapTiler) and to the launch region lives in
 * this file: the key rule, the style names, the URL pattern, the credit line and the cache
 * size. Changing provider or style starts here (ADR 0015).
 */

/** Stands in for the key so that it can never be printed. */
private const val REDACTED = "<map-key>"

private val mapKeyPattern = Regex("""[A-Za-z0-9_-]{8,64}""")

/** Matches a `key=` query parameter whatever its value, e.g. inside an error message. */
private val keyParameterPattern = Regex("""([?&]key=)[^&\s"']*""")

/**
 * Explains what is wrong with a map key, or returns null when it is fine. The same loose rule
 * as `app/build.gradle.kts` (URL-safe characters, sensible length). The message never contains
 * the value.
 */
fun mapKeyProblem(value: String): String? = when {
    value.isEmpty() -> "The map key is empty."
    !mapKeyPattern.matches(value) ->
        "The map key must be 8 to 64 characters, using only letters, digits, '-' and '_'."
    else -> null
}

/**
 * The provider key. A key inside an APK is not a secret (anyone can unpack the file); what
 * protects it is the restriction and the usage alert set in the provider's dashboard. It still
 * must not end up in logs, screenshots or bug reports, hence the wrapper.
 */
class MapKey(private val value: String) {
    init {
        mapKeyProblem(value)?.let { throw IllegalArgumentException(it) }
    }

    /** The only way to read the key. Call it where the request URL is built, nowhere else. */
    internal fun reveal(): String = value

    /** Removes this key, and any `key=` parameter, from [text]. */
    fun redact(text: String): String =
        keyParameterPattern.replace(text.replace(value, REDACTED)) { it.groupValues[1] + REDACTED }

    override fun toString(): String = "MapKey(hidden)"

    override fun equals(other: Any?): Boolean = other is MapKey && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/** A style address with the key in it. Only the map component may [reveal] it. */
class MapStyleUrl internal constructor(private val url: String) {
    internal fun reveal(): String = url

    override fun toString(): String = "MapStyleUrl(hidden)"
}

/**
 * The map provider settings of this build.
 *
 * @property key null when the build has no usable key; the map then shows "Map not configured".
 */
data class MapProviderConfig(val key: MapKey?) {

    val isConfigured: Boolean get() = key != null

    /** The style address for [variant], or null without a key. */
    fun styleUrl(variant: MapStyleVariant): MapStyleUrl? = key?.let {
        MapStyleUrl("$STYLE_BASE_URL${styleId(variant)}/style.json?key=${it.reveal()}")
    }

    /** Removes the key from [text] (an error message from the map library, say). */
    fun redact(text: String): String =
        key?.redact(text) ?: keyParameterPattern.replace(text) { it.groupValues[1] + REDACTED }

    companion object {
        /** MapTiler Cloud "Maps API". */
        private const val STYLE_BASE_URL = "https://api.maptiler.com/maps/"

        /** MapTiler's general street map and its dark counterpart. */
        const val LIGHT_STYLE_ID = "streets-v4"
        const val DARK_STYLE_ID = "streets-v4-dark"

        /** Where the credit line's links lead. Both providers require a visible credit. */
        const val PROVIDER_COPYRIGHT_URL = "https://www.maptiler.com/copyright/"
        const val DATA_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

        /**
         * Upper limit of the map's "ambient cache": the tiles it keeps on the phone as you look
         * around, so that places seen before still draw without a connection. Oldest tiles are
         * dropped first. 50 MB is roughly a city at street level. MapTiler's terms allow this
         * kind of temporary per-user cache; they do not allow bulk downloads.
         */
        const val AMBIENT_CACHE_BYTES: Long = 50L * 1024 * 1024

        fun styleId(variant: MapStyleVariant): String = when (variant) {
            MapStyleVariant.Light -> LIGHT_STYLE_ID
            MapStyleVariant.Dark -> DARK_STYLE_ID
        }

        /**
         * Builds the config from `BuildConfig` values. [configured] is false for the
         * placeholder and for CI's dummy key.
         */
        fun fromBuild(key: String, configured: Boolean): MapProviderConfig =
            MapProviderConfig(if (configured) MapKey(key) else null)
    }
}

/**
 * Where the map opens and how far it zooms, for the region the app launched in.
 *
 * SafeRoute launches in Kolkata, so the centre is the middle of that city. Further regions
 * arrive as configuration (`regionCode`, ADR 0013); nothing else in the code should name a
 * place. The centre is a public city-centre point, not anybody's location.
 */
object RegionDefaults {
    val camera = CameraState(target = LatLng(22.5726, 88.3639), zoom = 12.0)

    /** Zoomed out: the wider region. Further out is useless for getting around a city. */
    const val MIN_ZOOM: Double = 5.0

    /** Zoomed in: single buildings. The tiles have no more detail beyond this. */
    const val MAX_ZOOM: Double = 19.0
}
