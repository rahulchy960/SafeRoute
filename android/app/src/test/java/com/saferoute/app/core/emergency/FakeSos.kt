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
 * An SMS service that sends nothing. It records every attempt and answers what the test says:
 * [outcomes] holds an answer per number, each used once in order; when a number has none
 * left, [fallback] answers.
 */
class FakeSmsGateway(var fallback: SmsOutcome = SmsOutcome.Sent) : SmsGateway {
    /** One entry per attempt: the number and the text. Fake numbers only. */
    val attempts = CopyOnWriteArrayList<Pair<String, String>>()
    val outcomes = mutableMapOf<String, ArrayDeque<SmsOutcome>>()

    fun answer(phone: String, vararg results: SmsOutcome) {
        outcomes.getOrPut(phone) { ArrayDeque() }.addAll(results)
    }

    fun attemptsTo(phone: String): Int = attempts.count { it.first == phone }

    override suspend fun send(phoneE164: String, text: String): SmsOutcome {
        attempts += phoneE164 to text
        return outcomes[phoneE164]?.removeFirstOrNull() ?: fallback
    }
}

/** [SosActionStore] in a list, with the same "only while it is in one of these states" rule. */
class InMemorySosActionStore : SosActionStore {
    private val list = mutableListOf<SosAction>()

    val all: List<SosAction> get() = list.toList()

    override suspend fun actions(sosId: String): List<SosAction> = list.filter { it.clientSosId == sosId }

    override suspend fun insertAll(actions: List<SosAction>) {
        list += actions
    }

    override suspend fun moveIf(
        id: String,
        from: Set<SosActionState>,
        to: SosActionState,
        attemptCount: Int,
        errorCategory: String?,
        at: Instant,
    ): Boolean {
        val index = list.indexOfFirst { it.id == id && it.state in from }
        if (index < 0) return false
        list[index] = list[index].copy(
            state = to,
            attemptCount = attemptCount,
            lastErrorCategory = errorCategory,
            updatedAt = at,
        )
        return true
    }
}

/**
 * Replaces [SosDeviceModule] in every Hilt test: a test that starts the app never starts a
 * foreground service, never vibrates, never touches WorkManager and never sends an SMS.
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

    @Provides
    @Singleton
    fun provideFakeSmsGateway(): FakeSmsGateway = FakeSmsGateway()

    @Provides
    fun provideSmsGateway(fake: FakeSmsGateway): SmsGateway = fake
}
