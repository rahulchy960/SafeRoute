// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import android.database.sqlite.SQLiteConstraintException
import com.saferoute.app.core.data.local.SosActionEntity
import com.saferoute.app.core.data.local.SosDao
import com.saferoute.app.core.data.local.SosPointEntity
import com.saferoute.app.core.data.local.SosRecordEntity
import com.saferoute.app.core.emergency.SosAction
import com.saferoute.app.core.emergency.SosActionState
import com.saferoute.app.core.emergency.SosActionStore
import com.saferoute.app.core.emergency.SosActionType
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

    override suspend fun addPoint(id: String, point: SosPoint) {
        try {
            dao.insertPoint(
                SosPointEntity(
                    clientSosId = id,
                    lat = point.latitude,
                    lng = point.longitude,
                    accuracyM = point.accuracyMeters,
                    recordedAt = point.recordedAt.toEpochMilli(),
                    mockFlag = point.mock,
                ),
            )
        } catch (_: SQLiteConstraintException) {
            // The emergency was cancelled or wiped a moment ago: a point for a record that no
            // longer exists is dropped, which is what cancelling means.
        }
    }

    override suspend fun points(id: String): List<SosPoint> = dao.points(id).map {
        SosPoint(it.lat, it.lng, it.accuracyM, Instant.ofEpochMilli(it.recordedAt), it.mockFlag)
    }

    override suspend fun purgeBefore(cutoff: Instant) = dao.purgeBefore(cutoff.toEpochMilli())

    override suspend fun wipe() = dao.clear()
}

/** [SosActionStore] on the phone's Room database. */
class RoomSosActionStore @Inject constructor(private val dao: SosDao) : SosActionStore {

    override suspend fun actions(sosId: String): List<SosAction> = dao.actions(sosId).map {
        SosAction(
            id = it.id,
            clientSosId = it.clientSosId,
            contactId = it.contactLocalId,
            phoneE164 = it.phoneSnapshot,
            name = it.nameSnapshot,
            type = SosActionType.valueOf(it.type),
            state = SosActionState.valueOf(it.state),
            attemptCount = it.attemptCount,
            lastErrorCategory = it.lastErrorCategory,
            updatedAt = Instant.ofEpochMilli(it.updatedAt),
        )
    }

    override suspend fun insertAll(actions: List<SosAction>) {
        try {
            dao.insertActions(
                actions.map {
                    SosActionEntity(
                        id = it.id,
                        clientSosId = it.clientSosId,
                        contactLocalId = it.contactId,
                        phoneSnapshot = it.phoneE164,
                        nameSnapshot = it.name,
                        type = it.type.name,
                        state = it.state.name,
                        attemptCount = it.attemptCount,
                        lastErrorCategory = it.lastErrorCategory,
                        updatedAt = it.updatedAt.toEpochMilli(),
                    )
                },
            )
        } catch (_: SQLiteConstraintException) {
            // The emergency was cancelled or wiped a moment ago: there is nobody to tell.
        }
    }

    override suspend fun moveIf(
        id: String,
        from: Set<SosActionState>,
        to: SosActionState,
        attemptCount: Int,
        errorCategory: String?,
        at: Instant,
    ): Boolean = dao.moveActionIf(id, from.map { it.name }, to.name, attemptCount, errorCategory, at.toEpochMilli()) == 1
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
