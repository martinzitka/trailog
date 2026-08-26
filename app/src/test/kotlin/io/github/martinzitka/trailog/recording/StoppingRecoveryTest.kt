package io.github.martinzitka.trailog.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.RecordingSessionEntity
import io.github.martinzitka.trailog.data.TrailogDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recovery of a session interrupted **mid-save**.
 *
 * This is a regression test for a wedge found on the developer's phone: a session left in
 * `STOPPING` since 2026-08-19 made the app permanently unable to record. Startup recovery
 * deliberately skipped that state ("leave it for the UI"), and the UI renders `STOPPING` as a
 * bare "Saving activity…" spinner with no actions — so nothing could ever clear it, across
 * restarts and reinstalls.
 *
 * The user has already confirmed the stop by the time a session reaches `STOPPING`, so the only
 * unfinished work is the save. Completing it is the recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoppingRecoveryTest {

    private lateinit var db: TrailogDatabase
    private lateinit var engine: AndroidRecordingEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailogDatabase::class.java,
        ).build()
        engine = AndroidRecordingEngine(ApplicationProvider.getApplicationContext(), db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a session interrupted mid-save is finalised on startup`() = runTest {
        db.activityDao().upsert(activity())
        db.recordingSessionDao().upsert(session(RecordingState.STOPPING))

        engine.recoverInterruptedSession()

        assertNull("the session row must be gone", db.recordingSessionDao().active())
        assertNull("nothing may still be recording", engine.session.value)
    }

    @Test
    fun `finalising tolerates an activity row that is already gone`() = runTest {
        // Exactly the state found on the device: deleting an activity from History removes the
        // activity and its points but leaves the session row behind, so recovery meets a session
        // pointing at nothing. It must still clear the session rather than throw.
        db.recordingSessionDao().upsert(session(RecordingState.STOPPING))

        engine.recoverInterruptedSession()

        assertNull(db.recordingSessionDao().active())
        assertNull(engine.session.value)
    }

    @Test
    fun `a session awaiting the user's decision is left alone`() = runTest {
        // RECOVERING is not a dead end: the Record screen offers Resume and Finish, so the
        // choice belongs to the user and recovery must not pre-empt it.
        db.activityDao().upsert(activity())
        db.recordingSessionDao().upsert(session(RecordingState.RECOVERING))

        engine.recoverInterruptedSession()

        assertEquals(ACTIVITY_ID, db.recordingSessionDao().active()?.activityId)
        assertEquals(RecordingState.RECOVERING, engine.session.value?.state)
    }

    private fun activity() = ActivityEntity(
        id = ACTIVITY_ID,
        type = ActivityType.CYCLING.name,
        name = "",
        notes = null,
        startTime = START,
        createdAt = START,
        updatedAt = START,
    )

    private fun session(state: RecordingState) = RecordingSessionEntity(
        activityId = ACTIVITY_ID,
        type = ActivityType.CYCLING.name,
        state = state.name,
        startTime = START,
        currentSegmentIndex = 0,
        heartbeat = START + 110_000,
    )

    private companion object {
        const val ACTIVITY_ID = "01a019c7-0fb5-7e30-a52e-b39db0c642b8"
        const val START = 1_787_138_871_221L
    }
}
