// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import com.saferoute.app.core.data.local.SosDao
import com.saferoute.app.core.data.local.SosPointEntity
import com.saferoute.app.core.data.local.SosRecordEntity
import com.saferoute.app.core.emergency.SosPoint
import com.saferoute.app.core.emergency.SosRecord
import com.saferoute.app.core.emergency.SosState
import com.saferoute.app.core.emergency.SosStore
import java.time.Instant
import javax.inject.Inject

/** [SosStore] on the phone's Room database. Never touches the network. */
class RoomSosStore @Inject constructor(private val dao: SosDao) : SosStore {

    override suspend fun unresolved(): SosRecord? = dao.unresolved()?.toRecord()

    override suspend fun insertIfNoneUnresolved(record: SosRecord): SosRecord =
        dao.insertIfNoneUnresolved(record.toEntity()).toRecord()

    override suspend fun advanceIf(id: String, from: SosState, to: SosState, at: Instant): Boolean = dao.advanceIf(
        id = id,
        from = from,
        to = to,
        triggeredAt = at.toEpochMilli().takeIf { to == SosState.TRIGGERED_LOCAL },
        resolvedAt = at.toEpochMilli().takeIf { to == SosState.RESOLVED },
    ) == 1

    override suspend fun deleteIf(id: String, state: SosState): Boolean = dao.deleteIf(id, state) == 1

    override suspend fun addPoint(id: String, point: SosPoint) = dao.insertPoint(
        SosPointEntity(
            clientSosId = id,
            lat = point.latitude,
            lng = point.longitude,
            accuracyM = point.accuracyMeters,
            recordedAt = point.recordedAt.toEpochMilli(),
            mockFlag = point.mock,
        ),
    )

    override suspend fun points(id: String): List<SosPoint> = dao.points(id).map {
        SosPoint(it.lat, it.lng, it.accuracyM, Instant.ofEpochMilli(it.recordedAt), it.mockFlag)
    }

    override suspend fun purgeBefore(cutoff: Instant) = dao.purgeBefore(cutoff.toEpochMilli())

    override suspend fun wipe() = dao.clear()
}

private fun SosRecordEntity.toRecord() = SosRecord(
    clientSosId = clientSosId,
    state = state,
    entryPoint = entryPoint,
    practice = practice,
    startedAt = Instant.ofEpochMilli(startedAt),
    countdownEndsAt = Instant.ofEpochMilli(countdownEndsAt),
    triggeredAt = triggeredAt?.let(Instant::ofEpochMilli),
    resolvedAt = resolvedAt?.let(Instant::ofEpochMilli),
    syncState = syncState,
)

private fun SosRecord.toEntity() = SosRecordEntity(
    clientSosId = clientSosId,
    state = state,
    entryPoint = entryPoint,
    practice = practice,
    startedAt = startedAt.toEpochMilli(),
    countdownEndsAt = countdownEndsAt.toEpochMilli(),
    triggeredAt = triggeredAt?.toEpochMilli(),
    resolvedAt = resolvedAt?.toEpochMilli(),
    syncState = syncState,
)
