// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.network.generated.api.SearchApi
import com.saferoute.app.core.network.generated.model.SearchRequest
import com.saferoute.app.core.network.generated.model.SearchResults
import com.saferoute.app.core.network.networkJson
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response

/**
 * What the app sends for a search and how it reads the answer. The generated [SearchApi] is an
 * interface, so a hand-written fake stands in for the server: no network, no MockWebServer.
 */
class ApiSearchRepositoryTest {

    private class FakeSearchApi(private val answer: () -> Response<SearchResults>) : SearchApi {
        val sent = mutableListOf<SearchRequest>()

        override suspend fun searchPlaces(searchRequest: SearchRequest): Response<SearchResults> {
            sent += searchRequest
            return answer()
        }
    }

    /** A success body exactly as the server sends it (contract 0.4.0). */
    private val body = """
        {"results":[{"id":"fake-1","name":"Main Station","label":"Station Road, Example District",
        "latitude":10.5,"longitude":20.25,"kind":"station","futureField":true}],
        "attribution":"Fake provider · © Fake map data"}
    """.trimIndent()

    private fun parsed(json: String = body): SearchResults =
        networkJson.decodeFromString(SearchResults.serializer(), json)

    private fun ok(json: String = body) = FakeSearchApi { Response.success(parsed(json)) }

    private fun error(status: Int, code: String, retryAfter: String? = null) = FakeSearchApi {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.invalid/v1/search").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Error")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .build()
        val problem = """{"type":"about:blank","title":"T","status":$status,"detail":"D",""" +
            """"code":"$code","requestId":"fake-request-id"}"""
        Response.error(problem.toResponseBody("application/problem+json".toMediaType()), raw)
    }

    private suspend fun search(api: SearchApi, near: LatLng? = null, language: String = "en") =
        ApiSearchRepository(api).search("station", near, language)

    @Test
    fun `reads results with number coordinates and ignores fields it does not know`() = runTest {
        val outcome = search(ok()) as SearchOutcome.Found
        assertEquals(
            listOf(FoundPlace("fake-1", "Main Station", "Station Road, Example District", LatLng(10.5, 20.25), "station")),
            outcome.places,
        )
        assertEquals("Fake provider · © Fake map data", outcome.attribution)
    }

    @Test
    fun `the distance from the server is passed on, and its absence is not a zero`() = runTest {
        val json = """{"results":[
            {"id":"a","name":"Near","label":"","latitude":10.5,"longitude":20.5,"kind":"bank","distanceMeters":2300},
            {"id":"b","name":"Here","label":"","latitude":10.5,"longitude":20.5,"kind":"bank","distanceMeters":0},
            {"id":"c","name":"Unknown","label":"","latitude":10.5,"longitude":20.5,"kind":"bank"}
        ],"attribution":null}"""
        val found = search(ok(json)) as SearchOutcome.Found

        assertEquals(listOf(2300, 0, null), found.places.map { it.distanceMeters })
    }

    @Test
    fun `a chip sends its category and no text, and typed text sends no category`() = runTest {
        val api = ok()
        val repository = ApiSearchRepository(api)
        repository.search("", LatLng(10.123456, 20.987654), "en", SearchCategory.Pharmacy)
        repository.search("", null, "bn", SearchCategory.Transit)
        repository.search("bank", null, "en")

        assertEquals(listOf("pharmacy", "public_transport", null), api.sent.map { it.category })
        assertEquals(listOf(null, null, "bank"), api.sent.map { it.q })
        // The area is rounded for a chip exactly as for typed text.
        assertEquals(BigDecimal("10.12"), api.sent.first().nearLatitude)
        // The words on the wire, as the request body carries them.
        val body = networkJson.encodeToString(SearchRequest.serializer(), api.sent.first())
        assertEquals(true, "\"category\":\"pharmacy\"" in body)
        assertEquals(false, "\"q\"" in body)
    }

    @Test
    fun `every chip has a category the server knows`() {
        // The values of the contract's `category` (0.8.0) that the chips use.
        assertEquals(
            listOf("bank", "atm", "pharmacy", "hospital", "fuel", "restaurant", "grocery", "public_transport"),
            SearchCategory.entries.map { it.key },
        )
    }

    @Test
    fun `reads the searched circle, and an unknown centre is not taken for the user's location`() = runTest {
        fun answer(extra: String) = """{"results":[],"attribution":null$extra}"""
        suspend fun circle(extra: String) = (search(ok(answer(extra))) as SearchOutcome.Found).circle

        assertEquals(
            SearchedCircle(10, CircleCentre.SentArea),
            circle(""","searchedRadiusKm":10,"searchedAround":"near""""),
        )
        assertEquals(
            SearchedCircle(25, CircleCentre.TypedPlace),
            circle(""","searchedRadiusKm":25.0,"searchedAround":"placeHint""""),
        )
        assertEquals(
            SearchedCircle(10, CircleCentre.Unknown),
            circle(""","searchedRadiusKm":10,"searchedAround":"somethingNew""""),
        )
        assertEquals(SearchedCircle(10, CircleCentre.Unknown), circle(""","searchedRadiusKm":10"""))
        // A search by name: no circle, whatever else is there.
        assertNull(circle(""))
        assertNull(circle(""","searchedAround":"near""""))
    }

    @Test
    fun `matchType is accepted and an unknown value does not break the answer`() = runTest {
        val json = """{"results":[
            {"id":"a","name":"A","label":"","latitude":10.5,"longitude":20.5,"kind":"bank","matchType":"brand"},
            {"id":"b","name":"B","label":"","latitude":10.5,"longitude":20.5,"kind":"bank","matchType":"future"}
        ],"attribution":null,"searchedRadiusKm":10,"searchedAround":"near"}"""
        assertEquals(listOf("a", "b"), (search(ok(json)) as SearchOutcome.Found).places.map { it.id })
    }

    @Test
    fun `a null or blank attribution is no attribution, and no results is an empty list`() = runTest {
        val none = search(ok("""{"results":[],"attribution":null}""")) as SearchOutcome.Found
        assertEquals(emptyList<FoundPlace>(), none.places)
        assertNull(none.attribution)
        assertNull((search(ok("""{"results":[],"attribution":"  "}""")) as SearchOutcome.Found).attribution)
    }

    @Test
    fun `a coordinate sent as text is refused, not guessed`() {
        assertThrows(Exception::class.java) {
            parsed("""{"results":[{"id":"1","name":"n","label":"l","latitude":"10.5","longitude":20,"kind":"k"}],"attribution":null}""")
        }
    }

    @Test
    fun `sends the area rounded to two decimals, the language and a limit of six`() = runTest {
        val api = ok()
        search(api, near = LatLng(10.123456, 20.987654), language = "bn")
        search(api, near = LatLng(-10.126, -0.004), language = "en")
        // An unknown language falls back to English, the app's fallback.
        search(api, near = null, language = "hi")

        assertEquals(
            listOf(
                SearchRequest("station", BigDecimal("10.12"), BigDecimal("20.99"), SearchRequest.Language.bn, 6),
                SearchRequest("station", BigDecimal("-10.13"), BigDecimal("0.00"), SearchRequest.Language.en, 6),
                SearchRequest("station", null, null, SearchRequest.Language.en, 6),
            ),
            api.sent,
        )
    }

    @Test
    fun `the search goes in the request body as JSON, with numbers and nothing finer than two decimals`() = runTest {
        val api = ok()
        search(api, near = LatLng(10.123456, 20.987654), language = "bn")
        search(api, near = null, language = "en")

        fun wire(request: SearchRequest) = networkJson.encodeToString(SearchRequest.serializer(), request)
        // Coordinates are JSON numbers (not text), rounded; the precise digits are not in the body.
        assertEquals(
            """{"q":"station","nearLatitude":10.12,"nearLongitude":20.99,"language":"bn"}""",
            wire(api.sent[0]),
        )
        // No area known: none is sent. English and the limit of six are the server's defaults.
        assertEquals("""{"q":"station"}""", wire(api.sent[1]))
    }

    @Test
    fun `the generated call is a POST with a body and has no query parameters`() {
        val method = SearchApi::class.java.declaredMethods.single { it.name == "searchPlaces" }
        assertEquals("v1/search", method.getAnnotation(retrofit2.http.POST::class.java)?.value)
        assertNull(method.getAnnotation(retrofit2.http.GET::class.java))
        val parameterAnnotations = method.parameterAnnotations.flatMap { it.toList() }.map { it.annotationClass }
        assertEquals(listOf(retrofit2.http.Body::class), parameterAnnotations)
    }

    @Test
    fun `429 is rate limited with the server's wait, 503 is unavailable`() = runTest {
        assertEquals(
            SearchOutcome.Failed(SearchError.RateLimited(17)),
            search(error(429, "rate_limited", retryAfter = "17")),
        )
        assertEquals(SearchOutcome.Failed(SearchError.RateLimited(null)), search(error(429, "rate_limited")))
        // A date instead of seconds, or nonsense: treated as "no wait given".
        listOf("Wed, 21 Oct 2026 07:28:00 GMT", "-5", "0", "999999999").forEach { header ->
            assertEquals(
                header,
                SearchOutcome.Failed(SearchError.RateLimited(null)),
                search(error(429, "rate_limited", retryAfter = header)),
            )
        }
        listOf("search_unavailable", "search_not_configured", "db_unavailable", "a_future_code").forEach { code ->
            assertEquals(code, SearchOutcome.Failed(SearchError.Unavailable), search(error(503, code, retryAfter = "30")))
        }
    }

    @Test
    fun `no connection, and everything else, each have their own answer`() = runTest {
        assertEquals(
            SearchOutcome.Failed(SearchError.NoConnection),
            search(FakeSearchApi { throw IOException("fake: no route") }),
        )
        assertEquals(SearchOutcome.Failed(SearchError.Unexpected), search(error(400, "validation_error")))
        assertEquals(SearchOutcome.Failed(SearchError.Unexpected), search(error(500, "internal_error")))
    }

    @Test
    fun `401 and 403 get no special treatment here, the session handles sign-in`() = runTest {
        assertEquals(SearchOutcome.Failed(SearchError.Unexpected), search(error(401, "unauthorized")))
        listOf("forbidden", "bootstrap_required", "account_deleted").forEach { code ->
            assertEquals(code, SearchOutcome.Failed(SearchError.Unexpected), search(error(403, code)))
        }
    }
}
