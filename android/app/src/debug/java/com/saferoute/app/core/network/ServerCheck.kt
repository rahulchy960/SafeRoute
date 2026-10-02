// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.generated.api.OperationalApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/*
 * Debug builds only (src/debug). Release builds don't contain this file.
 */

/** The answer of one probe, in terms the developer screen can show. */
sealed interface ProbeResult {
    val requestId: String?

    /** The probe answered 200. [version] is the backend's build version (health only). */
    data class Up(val version: String?, override val requestId: String?) : ProbeResult

    data class Down(val failure: ApiFailure) : ProbeResult {
        override val requestId: String?
            get() = when (failure) {
                is ApiFailure.Problem -> failure.requestId
                is ApiFailure.Unauthorized -> failure.requestId
                is ApiFailure.Unexpected -> failure.requestId
                ApiFailure.NoConnection -> null
            }
    }
}

/**
 * Asks the backend's two public probes, `GET /health` and `GET /health/ready`, through the
 * app's real client (request id, user agent, token rule, retries). The screen gets
 * [ProbeResult]s and never a Retrofit or generated type.
 */
interface ServerCheck {
    /** False when the build has no `saferoute.apiBaseUrl`: then nothing is sent. */
    val isConfigured: Boolean

    suspend fun health(): ProbeResult

    suspend fun readiness(): ProbeResult
}

class ApiServerCheck @Inject constructor(
    private val config: ApiConfig,
    private val api: OperationalApi,
) : ServerCheck {

    override val isConfigured: Boolean get() = config.isConfigured

    override suspend fun health(): ProbeResult = when (val result = apiCall { api.getHealth() }) {
        is ApiResult.Success -> ProbeResult.Up(result.value.version, result.requestId)
        is ApiResult.Failure -> ProbeResult.Down(result.failure)
    }

    override suspend fun readiness(): ProbeResult = when (val result = apiCall { api.getReadiness() }) {
        is ApiResult.Success -> ProbeResult.Up(version = null, requestId = result.requestId)
        is ApiResult.Failure -> ProbeResult.Down(result.failure)
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface ServerCheckModule {

    @Binds
    fun bindServerCheck(check: ApiServerCheck): ServerCheck
}
