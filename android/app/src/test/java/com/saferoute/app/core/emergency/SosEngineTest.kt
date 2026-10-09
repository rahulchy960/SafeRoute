// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.data.RoomSosStore
import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.core.data.local.SosActionEntity
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.feature.search.TestClock
import java.io.File
import java.time.Instant
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val START_MILLIS = 1_760_000_000_000L

/**
 * The engine on a real SQLite FILE, so that "the process died" can be acted out: the database
 * is closed, every object is thrown away, and a new database, store and engine open the same
 * file, exactly what a restarted app does (failure rows 4, 5, 6 and 11 at data level).
 */
@RunWith(AndroidJUnit4::class)
class SosEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.cacheDir, "sos-engine-test.db").apply { delete() }
    private val clock = TestClock(nowMillis = START_MILLIS)

    private var database = open()
    private var engine = engineOn(database)

    private fun open(): SafeRouteDatabase =
        Room.databaseBuilder(context, SafeRouteDatabase::class.java, file.absolutePath)
            .addMigrations(*SafeRouteDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun engineOn(db: SafeRouteDatabase) =
        SosEngine(RoomSosStore(db.sosDao()), clock, UuidV7Generator(clock, Random(1)))

    /** Closes everything and opens the same file again with new objects. */
    private fun killAndRestartProcess() {
        database.close()
        database = open()
        engine = engineOn(database)
    }

    private fun advanceSeconds(seconds: Long) {
        clock.nowMillis += seconds * 1_000
    }

    @After
    fun tearDown() {
        database.close()
        file.delete()
    }

    @Test
    fun `starting writes a countdown record with its end time before anything else happens`() = runTest {
        val start = engine.startCountdown(SosEntryPoint.TILE)

        assertTrue(start is SosStart.Started)
        val record = engine.current()!!
        assertEquals(start.record, record)
        assertEquals(SosState.COUNTDOWN, record.state)
        assertEquals(SosEntryPoint.TILE, record.entryPoint)
        assertFalse(record.practice)
        assertEquals(Instant.ofEpochMilli(START_MILLIS), record.startedAt)
        assertEquals(Instant.ofEpochMilli(START_MILLIS + 5_000), record.countdownEndsAt)
        assertEquals(SosSyncState.NOT_SYNCED, record.syncState)
        assertNull(record.triggeredAt)
    }

    @Test
    fun `a second start in any unresolved state shows the existing emergency`() = runTest {
        val first = engine.startCountdown(SosEntryPoint.IN_APP).record

        val duringCountdown = engine.startCountdown(SosEntryPoint.WIDGET)
        assertEquals(SosStart.AlreadyActive(first), duringCountdown)

        advanceSeconds(5)
        engine.trigger()
        val afterTrigger = engine.startCountdown(SosEntryPoint.NOTIFICATION)
        assertTrue(afterTrigger is SosStart.AlreadyActive)
        assertEquals(first.clientSosId, afterTrigger.record.clientSosId)
        // The entry point stays the one that really started it.
        assertEquals(SosEntryPoint.IN_APP, afterTrigger.record.entryPoint)
    }

    @Test
    fun `ten starts at once make one emergency`() = runTest {
        val results = (1..10).map { async { engine.startCountdown(SosEntryPoint.IN_APP) } }.awaitAll()

        assertEquals(1, results.count { it is SosStart.Started })
        assertEquals(1, results.map { it.record.clientSosId }.toSet().size)
    }

    @Test
    fun `cancel during the countdown deletes the record and its points`() = runTest {
        val id = engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
        val store = RoomSosStore(database.sosDao())
        store.addPoint(id, point(1))

        assertTrue(engine.cancel())

        assertNull(engine.current())
        assertEquals(emptyList<SosPoint>(), store.points(id))
        assertEquals(SosRecovery.Nothing, engine.recovery())
        // Nothing to trigger afterwards, even when the time comes.
        advanceSeconds(10)
        assertNull(engine.trigger())
        assertFalse(engine.cancel())
    }

    @Test
    fun `the trigger does nothing before the end and happens exactly once at the end`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)

        advanceSeconds(4)
        assertNull("one second early", engine.trigger())
        assertEquals(SosState.COUNTDOWN, engine.current()!!.state)

        advanceSeconds(1)
        val triggered = engine.trigger()
        assertNotNull(triggered)
        assertEquals(SosState.TRIGGERED_LOCAL, triggered!!.state)
        assertEquals(Instant.ofEpochMilli(START_MILLIS + 5_000), engine.current()!!.triggeredAt)

        // A second timer, a duplicate callback, a late retry: none fires again.
        assertNull(engine.trigger())
        assertNull(engine.trigger(force = true))
    }

    @Test
    fun `ten triggers at once fire once`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)
        advanceSeconds(5)

        val results = (1..10).map { async { engine.trigger() } }.awaitAll()

        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `after the alert went out cancel is refused and I am safe resolves it`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)
        assertFalse("no alert went out yet", engine.resolve())
        advanceSeconds(5)
        engine.trigger()

        assertFalse(engine.cancel())
        assertTrue(engine.markActive())
        assertFalse("already active", engine.markActive())
        assertEquals(SosState.ACTIVE, engine.current()!!.state)
        assertFalse(engine.cancel())

        advanceSeconds(60)
        assertTrue(engine.resolve())
        assertNull(engine.current())
        assertFalse(engine.resolve())
        // A resolved one does not block the next emergency.
        assertTrue(engine.startCountdown(SosEntryPoint.IN_APP) is SosStart.Started)
    }

    @Test
    fun `process death during the countdown with time left resumes with what remains`() = runTest {
        val record = engine.startCountdown(SosEntryPoint.IN_APP).record
        advanceSeconds(2)

        killAndRestartProcess()

        val recovery = engine.recovery()
        assertTrue(recovery is SosRecovery.ResumeCountdown)
        assertEquals(record, (recovery as SosRecovery.ResumeCountdown).record)
        assertEquals(3_000, recovery.remaining.toMillis())
        // The rebuilt timer fires at the ORIGINAL end, not five seconds after the restart.
        advanceSeconds(3)
        assertNotNull(engine.trigger())
    }

    @Test
    fun `process death past the end of the countdown asks, and sends only on send now`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)
        advanceSeconds(3)
        killAndRestartProcess()
        advanceSeconds(300)

        assertTrue(engine.recovery() is SosRecovery.AskSendOrCancel)
        assertEquals("looking at it sends nothing", SosState.COUNTDOWN, engine.current()!!.state)

        val sent = engine.trigger(force = true)
        assertEquals(SosState.TRIGGERED_LOCAL, sent!!.state)
        assertNull(engine.trigger(force = true))
    }

    @Test
    fun `process death past the end of the countdown, then cancel, leaves nothing`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)
        killAndRestartProcess()
        advanceSeconds(300)

        assertTrue(engine.cancel())

        assertNull(engine.current())
        assertEquals(SosRecovery.Nothing, engine.recovery())
    }

    @Test
    fun `process death after the trigger and while active is found as still active`() = runTest {
        val id = engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
        advanceSeconds(5)
        engine.trigger()

        killAndRestartProcess()
        assertEquals(SosState.TRIGGERED_LOCAL, (engine.recovery() as SosRecovery.StillActive).record.state)
        assertNull("the trigger is not repeated after the restart", engine.trigger(force = true))

        engine.markActive()
        RoomSosStore(database.sosDao()).addPoint(id, point(1))
        killAndRestartProcess()
        advanceSeconds(86_400)

        val recovery = engine.recovery() as SosRecovery.StillActive
        assertEquals(SosState.ACTIVE, recovery.record.state)
        assertEquals(id, recovery.record.clientSosId)
        assertEquals(1, RoomSosStore(database.sosDao()).points(id).size)
        // A start from any entry point leads to it, not to a second one.
        assertTrue(engine.startCountdown(SosEntryPoint.TILE) is SosStart.AlreadyActive)
    }

    @Test
    fun `process death after resolved leaves nothing to recover`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)
        advanceSeconds(5)
        engine.trigger()
        engine.resolve()

        killAndRestartProcess()

        assertEquals(SosRecovery.Nothing, engine.recovery())
        assertNull(engine.current())
        assertFalse(engine.cancel())
        assertNull(engine.trigger(force = true))
    }

    @Test
    fun `points come back oldest first and never print where the user was`() = runTest {
        val id = engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
        val store = RoomSosStore(database.sosDao())
        store.addPoint(id, point(2))
        store.addPoint(id, point(1).copy(accuracyMeters = null, mock = true))

        val points = store.points(id)
        assertEquals(listOf(1L, 2L), points.map { it.recordedAt.epochSecond - START_MILLIS / 1_000 })
        assertEquals(listOf(true, false), points.map { it.mock })
        assertNull(points.first().accuracyMeters)

        val printed = "$points ${database.sosDao().points(id)} ${action(id)}"
        listOf("10.0", "20.0", "Test Contact", "9000010001").forEach { assertFalse(it, printed.contains(it)) }
    }

    @Test
    fun `the purge deletes what is older than thirty days and keeps the rest`() = runTest {
        val dao = database.sosDao()
        val store = RoomSosStore(dao)
        val old = engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
        store.addPoint(old, point(1))
        dao.insertActions(listOf(action(old)))
        advanceSeconds(5)
        engine.trigger()
        engine.resolve()

        advanceSeconds(SOS_RETENTION.seconds - 60)
        val recent = engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
        store.addPoint(recent, point(1).copy(recordedAt = clock.instant()))

        // One minute short of thirty days for the old one: nothing goes.
        store.purgeBefore(clock.instant().minus(SOS_RETENTION))
        assertEquals(1, store.points(old).size)

        advanceSeconds(120)
        store.purgeBefore(clock.instant().minus(SOS_RETENTION))

        assertEquals(emptyList<SosPoint>(), store.points(old))
        assertEquals(emptyList<SosActionEntity>(), dao.actions(old))
        assertEquals(recent, engine.current()!!.clientSosId)
        assertEquals(1, store.points(recent).size)
    }

    @Test
    fun `the wipe deletes every record, action and point`() = runTest {
        val dao = database.sosDao()
        val store = RoomSosStore(dao)
        val id = engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
        store.addPoint(id, point(1))
        dao.insertActions(listOf(action(id)))

        store.wipe()

        assertNull(engine.current())
        assertEquals(emptyList<SosPoint>(), store.points(id))
        assertEquals(emptyList<SosActionEntity>(), dao.actions(id))
    }

    // Round fixture values; not a place.
    private fun point(second: Long) = SosPoint(
        latitude = 10.0,
        longitude = 20.0,
        accuracyMeters = 5f,
        recordedAt = Instant.ofEpochMilli(START_MILLIS + second * 1_000),
        mock = false,
    )

    private fun action(sosId: String) = SosActionEntity(
        id = "action-1",
        clientSosId = sosId,
        contactLocalId = "id-1",
        phoneSnapshot = "+919000010001",
        nameSnapshot = "Test Contact 1",
        type = "SMS",
        state = "PENDING",
        attemptCount = 0,
        lastErrorCategory = null,
        updatedAt = START_MILLIS,
    )
}

/** A store that only remembers which clean-ups were asked for. */
private class RecordingSosStore : SosStore {
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun unresolved(): SosRecord? = null
    override suspend fun insertIfNoneUnresolved(record: SosRecord) = record
    override suspend fun advanceIf(id: String, from: SosState, to: SosState, at: Instant) = false
    override suspend fun deleteIf(id: String, state: SosState) = false
    override suspend fun addPoint(id: String, point: SosPoint) = Unit
    override suspend fun points(id: String) = emptyList<SosPoint>()
    override suspend fun purgeBefore(cutoff: Instant) {
        calls += "purgeBefore($cutoff)"
    }

    override suspend fun wipe() {
        calls += "wipe"
    }
}

/** Retention: the purge at app start and the wipe when the session ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosHousekeepingTest {

    private val store = RecordingSosStore()
    private val clock = TestClock(nowMillis = Instant.parse("2026-10-09T09:00:00Z").toEpochMilli())

    @Test
    fun `app start purges what is older than thirty days and wipes nothing while signed in`() = runTest {
        val session = FakeSession(SessionState.Ready)
        var scheduled = 0
        val housekeeping = SosHousekeeping(
            session,
            store,
            clock,
            { scheduled++ },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        housekeeping.start()
        housekeeping.start()

        assertEquals(listOf("purgeBefore(2026-09-09T09:00:00Z)"), store.calls.toList())
        assertEquals("the daily job is planned once", 1, scheduled)
    }

    @Test
    fun `sign-out, the start screen and a blocked account wipe everything`() = runTest {
        val session = FakeSession(SessionState.Ready)
        SosHousekeeping(session, store, clock, {}, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).start()
        store.calls.clear()

        for (state in listOf(
            SessionState.Loading,
            SessionState.Error(retryable = true),
            SessionState.NeedsConsent,
            SessionState.NeedsBootstrap,
        )) {
            session.setState(state)
        }
        assertEquals("nothing is known for sure in these states", emptyList<String>(), store.calls.toList())

        session.setState(SessionState.SignedOut)
        session.setState(SessionState.Ready)
        session.setState(SessionState.Blocked(BlockReason.ACCOUNT_DELETED))
        session.setState(SessionState.NeedsAge)

        assertEquals(listOf("wipe", "wipe", "wipe"), store.calls.toList())
    }
}

/** The emergency core keeps its promises by not having the means to break them. */
class SosCoreBoundaryTest {

    private val files = File("src/main/java/com/saferoute/app/core/emergency").listFiles().orEmpty().toList() +
        listOf("RoomSosStore.kt", "local/SosDao.kt", "local/SosEntities.kt", "local/Migrations.kt")
            .map { File("src/main/java/com/saferoute/app/core/data/$it") }

    @Test
    fun `it never logs, never touches the network and sends no message`() {
        assertTrue(files.size >= 10 && files.all { it.isFile })
        val forbidden = listOf(
            "android.util.Log",
            "println(",
            "core.network",
            "okhttp3",
            "retrofit2",
            "SmsManager",
            "SEND_SMS",
            "SharedPreferences",
            "datastore",
        )
        val offenders = files.flatMap { file ->
            file.readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .filter { line -> forbidden.any(line::contains) }
                .map { file.name to it.trim() }
        }

        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }
}
