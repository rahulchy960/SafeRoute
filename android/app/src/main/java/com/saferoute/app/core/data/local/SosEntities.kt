// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saferoute.app.core.emergency.SosEntryPoint
import com.saferoute.app.core.emergency.SosState
import com.saferoute.app.core.emergency.SosSyncState

/**
 * One emergency (ADR 0027). Room stores an enum as its name, so the columns read `COUNTDOWN`,
 * `TILE` and so on. Times are milliseconds since 1970 in UTC. The row holds no personal data.
 */
@Entity(tableName = "sos_records")
data class SosRecordEntity(
    @PrimaryKey val clientSosId: String,
    val state: SosState,
    val entryPoint: SosEntryPoint,
    val practice: Boolean,
    val startedAt: Long,
    val countdownEndsAt: Long,
    val triggeredAt: Long?,
    val resolvedAt: Long?,
    @ColumnInfo(defaultValue = "NOT_SYNCED") val syncState: SosSyncState,
)

/**
 * One thing done for one contact during an emergency, for example an SMS (filled from P014b).
 * The name and number are COPIES taken when the emergency began, so that a later change of the
 * contact list cannot change who was told.
 *
 * PERSONAL DATA of people who are not users: [phoneSnapshot] and [nameSnapshot]. Deleted with
 * the record (`CASCADE`), on sign-out and after the retention period.
 */
@Entity(
    tableName = "sos_actions",
    foreignKeys = [
        ForeignKey(
            entity = SosRecordEntity::class,
            parentColumns = ["clientSosId"],
            childColumns = ["clientSosId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clientSosId")],
)
data class SosActionEntity(
    /** A UUID; later the idempotency key of the action. */
    @PrimaryKey val id: String,
    val clientSosId: String,
    val contactLocalId: String,
    val phoneSnapshot: String,
    val nameSnapshot: String,
    val type: String,
    /** PENDING, IN_PROGRESS, SENT, FAILED_RETRYABLE, FAILED_FINAL or SKIPPED. */
    val state: String,
    val attemptCount: Int,
    val lastErrorCategory: String?,
    val updatedAt: Long,
) {
    override fun toString(): String = "SosActionEntity(hidden)"
}

/**
 * One position of the trail kept during an emergency. PERSONAL DATA: where the user was.
 * Deleted with the record, on sign-out and after the retention period; never backed up.
 */
@Entity(
    tableName = "sos_points",
    foreignKeys = [
        ForeignKey(
            entity = SosRecordEntity::class,
            parentColumns = ["clientSosId"],
            childColumns = ["clientSosId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clientSosId")],
)
data class SosPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientSosId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val recordedAt: Long,
    val mockFlag: Boolean,
) {
    override fun toString(): String = "SosPointEntity(hidden)"
}
