// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.saferoute.app.core.emergency.SosState

/**
 * Reads and writes the three emergency tables. The statements with a `WHERE ... state = ...`
 * return how many rows they changed: 1 means "it was in that state and I changed it", 0 means
 * "someone else was first, or it is gone". That is what makes a trigger happen once.
 */
@Dao
interface SosDao {

    @Query("SELECT * FROM sos_records WHERE state != 'RESOLVED' ORDER BY startedAt DESC LIMIT 1")
    suspend fun unresolved(): SosRecordEntity?

    @Insert
    suspend fun insert(record: SosRecordEntity)

    /** `@Transaction`: the look and the insert are one step, so two callers cannot both insert. */
    @Transaction
    suspend fun insertIfNoneUnresolved(record: SosRecordEntity): SosRecordEntity {
        unresolved()?.let { return it }
        insert(record)
        return record
    }

    @Query(
        "UPDATE sos_records SET state = :to, " +
            "triggeredAt = COALESCE(:triggeredAt, triggeredAt), " +
            "resolvedAt = COALESCE(:resolvedAt, resolvedAt) " +
            "WHERE clientSosId = :id AND state = :from",
    )
    suspend fun advanceIf(id: String, from: SosState, to: SosState, triggeredAt: Long?, resolvedAt: Long?): Int

    /** Its actions and points go with it (`ON DELETE CASCADE`). */
    @Query("DELETE FROM sos_records WHERE clientSosId = :id AND state = :state")
    suspend fun deleteIf(id: String, state: SosState): Int

    @Insert
    suspend fun insertPoint(point: SosPointEntity)

    @Query("SELECT * FROM sos_points WHERE clientSosId = :id ORDER BY recordedAt ASC, id ASC")
    suspend fun points(id: String): List<SosPointEntity>

    @Insert
    suspend fun insertActions(actions: List<SosActionEntity>)

    /** In the order they were created (SQLite numbers rows as they are inserted). */
    @Query("SELECT * FROM sos_actions WHERE clientSosId = :id ORDER BY rowid ASC")
    suspend fun actions(id: String): List<SosActionEntity>

    /** Changes one action only while it is in one of the states [from]; 1 when it did. */
    @Query(
        "UPDATE sos_actions SET state = :to, attemptCount = :attemptCount, " +
            "lastErrorCategory = :errorCategory, updatedAt = :at " +
            "WHERE id = :id AND state IN (:from)",
    )
    suspend fun moveActionIf(
        id: String,
        from: List<String>,
        to: String,
        attemptCount: Int,
        errorCategory: String?,
        at: Long,
    ): Int

    @Query("DELETE FROM sos_records WHERE startedAt < :cutoff")
    suspend fun deleteRecordsBefore(cutoff: Long)

    @Query("DELETE FROM sos_points WHERE recordedAt < :cutoff")
    suspend fun deletePointsBefore(cutoff: Long)

    @Transaction
    suspend fun purgeBefore(cutoff: Long) {
        deleteRecordsBefore(cutoff)
        deletePointsBefore(cutoff)
    }

    /** Everything: the actions and points are deleted with their records. */
    @Query("DELETE FROM sos_records")
    suspend fun clear()
}
