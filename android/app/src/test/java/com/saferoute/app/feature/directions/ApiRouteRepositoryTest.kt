// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.network.generated.api.RoutingApi
import com.saferoute.app.core.network.generated.model.RouteRequest
import com.saferoute.app.core.network.generated.model.Routes
import com.saferoute.app.core.network.networkJson
import java.io.IOException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * What the app sends for a route and how it reads every answer, and the polyline decoder. The
 * generated [RoutingApi] is an interface, so a hand-written fake stands in for the server: no
 * network. Coordinates are round invented values.
 */
class ApiRouteRepositoryTest {

    private class FakeRoutingApi(private val answer: () -> Response<Routes>) : RoutingApi {
        val sent = mutableListOf<RouteRequest>()

        override suspend fun createRoutes(routeRequest: RouteRequest): Response<Routes> {
            sent += routeRequest
            return answer()
        }
    }

    /** (10.0, 20.0) → (10.25, 20.25) → (10.5, 20.5) as polyline6. */
    private val line = encodePolyline6(listOf(10.0 to 20.0, 10.25 to 20.25, 10.5 to 20.5))

    /** A success body exactly as the server sends it (contract 0.6.0), plus a field from the future. */
    private fun body(geometry: String = line, routes: Int = 2) = """
        {"routes":[${(1..routes).joinToString(",") { n ->
        """{"id":"fake-route-$n","distanceMeters":${2000 + n * 100},"durationSeconds":${1500 + n * 60},
            "geometry":{"encoding":"polyline6","value":"${geometry.replace("\\", "\\\\")}"},
            "bbox":[20.0,10.0,20.5,10.5],"futureField":true}"""
    }}],"attribution":"© Fake map data"}
    """.trimIndent()

    private fun ok(json: String = body()) =
        FakeRoutingApi { Response.success(networkJson.decodeFromString(Routes.serializer(), json)) }

    private fun error(status: Int, code: String, retryAfter: String? = null) = FakeRoutingApi {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.invalid/v1/routes").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Error")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .build()
        val problem = """{"type":"about:blank","title":"T","status":$status,"detail":"D",""" +
            """"code":"$code","requestId":"fake-request-id"}"""
        Response.error(problem.toResponseBody("application/problem+json".toMediaType()), raw)
    }

    private val origin = LatLng(10.1234567, 20.7654321)
    private val destination = LatLng(10.5, 20.5)

    private suspend fun ask(api: RoutingApi, mode: TravelMode = TravelMode.Walking) =
        ApiRouteRepository(api, UnconfinedTestDispatcher()).routes(origin, destination, mode)

    @Test
    fun `sends origin, destination and mode in the body, as numbers with at most six decimals`() = runTest {
        val api = ok()
        ask(api, TravelMode.Driving)
        val sent = networkJson.encodeToString(RouteRequest.serializer(), api.sent.single())
        assertEquals(
            """{"origin":{"latitude":10.123457,"longitude":20.765432},""" +
                """"destination":{"latitude":10.5,"longitude":20.5},"mode":"driving"}""",
            sent,
        )
        ask(api)
        assertEquals(RouteRequest.Mode.walking, api.sent.last().mode)
        // The generated call is a POST with one body and nothing in the URL (ADR 0019).
        val method = RoutingApi::class.java.declaredMethods.single { it.name == "createRoutes" }
        assertEquals("v1/routes", method.getAnnotation(retrofit2.http.POST::class.java)!!.value)
        val annotations = method.parameterAnnotations.flatMap { it.toList() }.map { it.annotationClass }
        assertTrue(retrofit2.http.Body::class in annotations)
        assertTrue(annotations.none { it == retrofit2.http.Query::class || it == retrofit2.http.Path::class })
    }

    @Test
    fun `reads the routes, fastest first, decodes their lines and keeps the credit`() = runTest {
        val found = ask(ok()) as RouteOutcome.Found
        assertEquals(listOf("fake-route-1", "fake-route-2"), found.routes.map { it.id })
        assertEquals(2100, found.routes[0].distanceMeters)
        assertEquals(1560, found.routes[0].durationSeconds)
        assertEquals(listOf(LatLng(10.0, 20.0), LatLng(10.25, 20.25), LatLng(10.5, 20.5)), found.routes[0].points)
        assertEquals("© Fake map data", found.attribution)
    }

    @Test
    fun `a line that cannot be read, or no route at all, is not drawn`() = runTest {
        assertEquals(RouteOutcome.Failed(RouteError.Unavailable), ask(ok(body(geometry = "not a polyline !!"))))
        assertEquals(RouteOutcome.Failed(RouteError.Unavailable), ask(ok(body(geometry = ""))))
        assertEquals(RouteOutcome.Failed(RouteError.Unavailable), ask(ok(body(routes = 0))))
    }

    @Test
    fun `every problem code has its own meaning`() = runTest {
        val expected = mapOf(
            error(422, "outside_covered_area") to RouteError.OutsideCovered,
            error(404, "no_route_found") to RouteError.NoRoute,
            error(422, "location_not_routable") to RouteError.NotRoutable,
            error(422, "route_too_long") to RouteError.TooLong,
            error(429, "rate_limited", retryAfter = "9") to RouteError.RateLimited(9),
            error(429, "rate_limited") to RouteError.RateLimited(null),
            // The routing service is waking up: 503 with the seconds to wait.
            error(503, "routing_unavailable", retryAfter = "10") to RouteError.Starting(10),
            error(503, "routing_unavailable") to RouteError.Unavailable,
            error(503, "routing_not_configured") to RouteError.Unavailable,
            error(503, "db_unavailable", retryAfter = "3") to RouteError.Starting(3),
            // Unknown codes and sign-in problems never look like an answer about the places.
            error(422, "a_code_from_the_future") to RouteError.Unavailable,
            error(500, "internal_error") to RouteError.Unavailable,
            error(403, "account_deleted") to RouteError.Unavailable,
            error(401, "unauthorized") to RouteError.Unavailable,
            error(400, "validation_error") to RouteError.Unavailable,
        )
        for ((api, error) in expected) {
            assertEquals(RouteOutcome.Failed(error), ask(api))
            assertEquals("one request, never a retry", 1, api.sent.size)
        }
    }

    @Test
    fun `no connection is offline`() = runTest {
        assertEquals(RouteOutcome.Failed(RouteError.Offline), ask(FakeRoutingApi { throw IOException("fake") }))
    }

    // --- the decoder ---------------------------------------------------------------------

    @Test
    fun `decodes the well-known example line`() {
        // The textbook polyline (38.5,-120.2) (40.7,-120.95) (43.252,-126.453), six decimals.
        assertEquals(
            listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453)),
            decodePolyline6("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"),
        )
    }

    @Test
    fun `keeps six decimals, handles negative and tiny steps, and an empty text is an empty line`() {
        val points = listOf(-0.000001 to 0.000001, -33.123456 to -70.654321, 0.0 to 179.999999, 89.999999 to -180.0)
        assertEquals(points.map { LatLng(it.first, it.second) }, decodePolyline6(encodePolyline6(points)))
        assertEquals(emptyList<LatLng>(), decodePolyline6(""))
    }

    @Test
    fun `rejects text that is not a complete polyline`() {
        val good = encodePolyline6(listOf(10.5 to 20.5, 10.6 to 20.6))
        assertNull("cut off inside a number", decodePolyline6(good.dropLast(1) + "~"))
        assertNull("a latitude without a longitude", decodePolyline6(encodePolyline6(listOf(10.5 to 20.5)).take(5)))
        assertNull("characters outside the alphabet", decodePolyline6("hello world !!"))
        assertNull("a latitude beyond the pole", decodePolyline6(encodePolyline6(listOf(95.0 to 20.0))))
        assertNull("a number that never ends", decodePolyline6("~".repeat(40)))
    }

    @Test
    fun `decodes a long line quickly enough to do off the main thread`() {
        val long = encodePolyline6((0 until 50_000).map { 10.0 + it * 0.00001 to 20.0 + it * 0.00002 })
        assertEquals(50_000, decodePolyline6(long)!!.size)
    }
}

/** The reverse of the decoder, for building test lines from (latitude, longitude) pairs. */
internal fun encodePolyline6(points: List<Pair<Double, Double>>): String {
    val out = StringBuilder()
    var previousLat = 0L
    var previousLng = 0L
    fun encode(value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20L or (v and 0x1F)) + 63).toInt().toChar())
            v = v shr 5
        }
        out.append((v + 63).toInt().toChar())
    }
    for ((lat, lng) in points) {
        val latE6 = Math.round(lat * 1e6)
        val lngE6 = Math.round(lng * 1e6)
        encode(latE6 - previousLat)
        encode(lngE6 - previousLng)
        previousLat = latE6
        previousLng = lngE6
    }
    return out.toString()
}
