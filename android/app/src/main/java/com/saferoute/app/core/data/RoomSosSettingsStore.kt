// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import com.saferoute.app.core.data.local.SosSettingsDao
import com.saferoute.app.core.data.local.SosSettingsEntity
import com.saferoute.app.core.emergency.AlertLanguageChoice
import com.saferoute.app.core.emergency.SosSettings
import com.saferoute.app.core.emergency.SosSettingsStore
import com.saferoute.app.core.emergency.messageName
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [SosSettingsStore] on the phone's Room database. Never touches the network, never logs. */
@Singleton
class RoomSosSettingsStore @Inject constructor(private val dao: SosSettingsDao) : SosSettingsStore {

    // Read, change, write: one at a time, so that two changes made at once both stay.
    private val writes = Mutex()

    override val settings: Flow<SosSettings> = dao.observe().map { it.toSettings() }

    override suspend fun current(): SosSettings = dao.get().toSettings()

    override suspend fun setName(name: String?) = change { it.copy(name = messageName(name)) }

    override suspend fun setLanguage(language: AlertLanguageChoice) = change { it.copy(language = language) }

    override suspend fun setCountdownSound(on: Boolean) = change { it.copy(countdownSound = on) }

    override suspend fun clear() = writes.withLock { dao.clear() }

    private suspend fun change(update: (SosSettings) -> SosSettings) = writes.withLock {
        val next = update(dao.get().toSettings())
        dao.upsert(SosSettingsEntity(alertName = next.name, alertLanguage = next.language, countdownSound = next.countdownSound))
    }
}

private fun SosSettingsEntity?.toSettings(): SosSettings =
    if (this == null) SosSettings() else SosSettings(alertName, alertLanguage, countdownSound)
