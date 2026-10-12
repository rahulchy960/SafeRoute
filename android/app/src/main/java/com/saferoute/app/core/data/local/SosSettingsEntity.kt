// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.saferoute.app.core.emergency.AlertLanguageChoice
import kotlinx.coroutines.flow.Flow

/** The id of the one row the table ever holds. */
internal const val SETTINGS_ROW_ID = 1

/**
 * What the user chose for their alerts (P014c2b). A table with ONE row: the name is a piece
 * of personal data, and the app's settings file (DataStore) may hold flags only, so it lives
 * here, in the private database, next to the contacts it is used with.
 */
@Entity(tableName = "sos_settings")
data class SosSettingsEntity(
    @PrimaryKey val id: Int = SETTINGS_ROW_ID,
    /** The name in the alert; null when none was entered. */
    val alertName: String?,
    val alertLanguage: AlertLanguageChoice,
    val countdownSound: Boolean,
) {
    // The generated toString() would print the name.
    override fun toString(): String = "SosSettingsEntity(hidden)"
}

@Dao
interface SosSettingsDao {

    /** Null until something was chosen: the caller then uses the defaults. */
    @Query("SELECT * FROM sos_settings WHERE id = $SETTINGS_ROW_ID")
    fun observe(): Flow<SosSettingsEntity?>

    @Query("SELECT * FROM sos_settings WHERE id = $SETTINGS_ROW_ID")
    suspend fun get(): SosSettingsEntity?

    @Upsert
    suspend fun upsert(settings: SosSettingsEntity)

    @Query("DELETE FROM sos_settings")
    suspend fun clear()
}
