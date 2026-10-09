// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.feature.emergency.SosDeviceModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton
import kotlinx.coroutines.test.TestCoroutineScheduler

/*
 * Stand-ins for the parts of an emergency that need a phone: no service, no vibration, no
 * battery, no job scheduler.
 */

/**
 * A clock that follows the test's virtual time, so that `delay(1000)` and "one second later
 * on the clock" are the same thing. [shiftMillis] acts out a change of the phone's clock.
 */
class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
    private val startMillis: Long = 1_760_000_000_000L,
) : Clock() {
    var shiftMillis = 0L

    override fun instant(): Instant = Instant.ofEpochMilli(startMillis + scheduler.currentTime + shiftMillis)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/** [SosStore] in a list, with the same "only if it is still in that state" rules as Room. */
class InMemorySosStore : SosStore {
    private val records = mutableListOf<SosRecord>()
    private val trail = mutableMapOf<String, MutableList<SosPoint>>()

    val all: List<SosRecord> get() = records.toList()

    override suspend fun unresolved(): SosRecord? = records.lastOrNull { it.state != SosState.RESOLVED }

    override suspend fun insertIfNoneUnresolved(record: SosRecord): SosRecord =
        records.lastOrNull { it.state != SosState.RESOLVED } ?: record.also { records += it }

    override suspend fun advanceIf(id: String, from: SosState, to: SosState, at: Instant): Boolean {
        val index = records.indexOfFirst { it.clientSosId == id && it.state == from }
        if (index < 0) return false
        records[index] = records[index].copy(
            state = to,
            triggeredAt = if (to == SosState.TRIGGERED_LOCAL) at else records[index].triggeredAt,
            resolvedAt = if (to == SosState.RESOLVED) at else records[index].resolvedAt,
        )
        return true
    }

    override suspend fun deleteIf(id: String, state: SosState): Boolean {
        val removed = records.removeAll { it.clientSosId == id && it.state == state }
        if (removed) trail.remove(id)
        return removed
    }

    override suspend fun addPoint(id: String, point: SosPoint) {
        // Like the real one: a point for a record that is gone is dropped.
        if (records.any { it.clientSosId == id }) trail.getOrPut(id) { mutableListOf() } += point
    }

    override suspend fun points(id: String): List<SosPoint> = trail[id].orEmpty().sortedBy { it.recordedAt }

    override suspend fun purgeBefore(cutoff: Instant) {
        records.removeAll { it.startedAt.isBefore(cutoff) }
    }

    override suspend fun wipe() {
        records.clear()
        trail.clear()
    }
}

class FakeSosHost : SosHost {
    val events = CopyOnWriteArrayList<String>()
    val running: Boolean get() = events.lastOrNull() == "begin"

    override fun begin() {
        events += "begin"
    }

    override fun end() {
        events += "end"
    }
}

class FakeSosHaptics : SosHaptics {
    val events = CopyOnWriteArrayList<String>()

    override fun countdownTick() {
        events += "tick"
    }

    override fun triggered() {
        events += "triggered"
    }
}

class FakeBatteryLevel(var percent: Int? = 80) : BatteryLevel {
    override fun percent(): Int? = percent
}

class FakePurgeScheduler : PurgeScheduler {
    var scheduled = 0

    override fun scheduleDaily() {
        scheduled++
    }
}

/**
 * Replaces [SosDeviceModule] in every Hilt test: a test that starts the app never starts a
 * foreground service, never vibrates and never touches WorkManager.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SosDeviceModule::class])
object FakeSosDeviceModule {

    @Provides
    @Singleton
    fun provideFakeHost(): FakeSosHost = FakeSosHost()

    @Provides
    fun provideHost(fake: FakeSosHost): SosHost = fake

    @Provides
    @Singleton
    fun provideFakeHaptics(): FakeSosHaptics = FakeSosHaptics()

    @Provides
    fun provideHaptics(fake: FakeSosHaptics): SosHaptics = fake

    @Provides
    @Singleton
    fun provideFakeBattery(): FakeBatteryLevel = FakeBatteryLevel()

    @Provides
    fun provideBattery(fake: FakeBatteryLevel): BatteryLevel = fake

    @Provides
    @Singleton
    fun provideFakeScheduler(): FakePurgeScheduler = FakePurgeScheduler()

    @Provides
    fun provideScheduler(fake: FakePurgeScheduler): PurgeScheduler = fake
}
