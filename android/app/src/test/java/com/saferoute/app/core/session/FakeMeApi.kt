// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.model.BootstrapMeRequest
import com.saferoute.app.core.network.generated.model.Consent
import com.saferoute.app.core.network.generated.model.ConsentList
import com.saferoute.app.core.network.generated.model.Me
import com.saferoute.app.core.network.generated.model.SetConsentRequest
import com.saferoute.app.core.network.problemJson
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * The SafeRoute API as a plain object: no HTTP, no threads, so a test can use virtual time and
 * decide exactly when an answer arrives. (Tests of the HTTP details use `FakeApi` with a local
 * server instead.)
 *
 * Each route has a list of answers, used in order; the last one repeats.
 */
class FakeMeApi : MeApi {

    /** What one call does. */
    sealed interface Answer {
        /** A success with the given HTTP status. */
        data class Ok(val status: Int = 200) : Answer

        /** A problem response, for example `Problem(403, "bootstrap_required")`. */
        data class Problem(val status: Int, val code: String) : Answer

        /** The connection fails. */
        data object NoConnection : Answer

        /** The server never answers (until the caller gives up or is cancelled). */
        data object Hangs : Answer
    }

    var getMe: List<Answer> = listOf(Answer.Ok())
    var bootstrap: List<Answer> = listOf(Answer.Ok(201))
    var getConsents: List<Answer> = listOf(Answer.Ok())
    var putConsent: List<Answer> = listOf(Answer.Ok())

    /** What `GET /v1/me/consents` returns on success. */
    var consents: List<Consent> = listOf(consent())

    /** How long every call takes, in virtual milliseconds. Gives a test room to interleave. */
    var latencyMillis: Long = 0

    /** One entry per call, for example `GET /v1/me`. */
    val calls = CopyOnWriteArrayList<String>()

    /** The body of every bootstrap call. */
    val bootstrapBodies = CopyOnWriteArrayList<BootstrapMeRequest>()

    private val served = mutableMapOf<String, Int>()

    private suspend fun <T : Any> answer(route: String, answers: List<Answer>, body: () -> T): Response<T> {
        calls += route
        delay(latencyMillis)
        val index = served.merge(route, 1, Int::plus)!! - 1
        return when (val answer = answers[index.coerceAtMost(answers.lastIndex)]) {
            is Answer.Ok -> Response.success(answer.status, body())
            is Answer.Problem -> Response.error(
                answer.status,
                problemJson(answer.status, answer.code).toResponseBody("application/problem+json".toMediaType()),
            )
            Answer.NoConnection -> throw IOException("fake: no connection")
            Answer.Hangs -> awaitCancellation()
        }
    }

    override suspend fun getMe(): Response<Me> = answer(GET_ME, getMe) { ME }

    override suspend fun bootstrapMe(bootstrapMeRequest: BootstrapMeRequest?): Response<Me> {
        bootstrapMeRequest?.let { bootstrapBodies += it }
        return answer(POST_BOOTSTRAP, bootstrap) { ME }
    }

    override suspend fun getMyConsents(): Response<ConsentList> =
        answer(GET_CONSENTS, getConsents) { ConsentList(consents) }

    override suspend fun setMyConsent(purpose: String, setConsentRequest: SetConsentRequest): Response<Consent> =
        answer("PUT /v1/me/consents/$purpose", putConsent) { consent() }

    companion object {
        /** A made-up account; the phone number is the usual all-zero placeholder. */
        val ME = Me(
            id = UUID.fromString("0f8c2a4e-6b1d-4c3a-9e7f-2d5b8a1c4e60"),
            phoneE164 = "+910000000001",
            displayName = null,
            locale = "en",
            role = "user",
            createdAt = OffsetDateTime.parse("2026-10-01T09:30:00Z"),
        )

        fun consent(
            purpose: String = PURPOSE_ACCOUNT_CORE,
            status: String = "granted",
            noticeVersion: String = NOTICE_VERSION,
        ) = Consent(purpose, status, noticeVersion, OffsetDateTime.parse("2026-10-06T09:30:00Z"))
    }
}
