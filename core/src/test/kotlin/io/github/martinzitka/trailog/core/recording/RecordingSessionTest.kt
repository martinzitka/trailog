package io.github.martinzitka.trailog.core.recording

import io.github.martinzitka.trailog.core.model.ActivityType
import kotlinx.datetime.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class RecordingSessionTest {

    private val id = UUID.fromString("018f0000-0000-7000-8000-000000000000")
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)

    private fun session() = RecordingSession.start(id, ActivityType.CYCLING, now = t0)

    @Test
    fun `a started session is recording, on segment 0, with no fixes and an initial heartbeat`() {
        val s = session()
        assertEquals(RecordingState.RECORDING, s.state)
        assertEquals(0, s.currentSegmentIndex)
        assertNull(s.lastFixTime)
        assertEquals(t0, s.startTime)
        assertEquals(t0, s.heartbeat)
        assertEquals(ActivityType.CYCLING, s.type)
    }

    @Test
    fun `apply advances the state via the machine`() {
        val paused = session().apply(RecordingCommand.PAUSE)
        assertEquals(RecordingState.PAUSED, paused.state)
        // Non-state fields are untouched.
        assertEquals(0, paused.currentSegmentIndex)
        assertEquals(id, paused.activityId)
    }

    @Test
    fun `apply rejects an illegal command`() {
        assertFailsWith<IllegalRecordingTransition> {
            session().apply(RecordingCommand.RESUME) // RECORDING has no RESUME
        }
    }

    @Test
    fun `starting a new segment increments the index and leaves the id fixed`() {
        val s = session().startNewSegment()
        assertEquals(1, s.currentSegmentIndex)
        assertEquals(id, s.activityId)
        assertEquals(2, s.startNewSegment().currentSegmentIndex)
    }

    @Test
    fun `withFixAt records the last fix time`() {
        val fixTime = t0 + 3.minutes
        assertEquals(fixTime, session().withFixAt(fixTime).lastFixTime)
    }

    @Test
    fun `withHeartbeatAt records the heartbeat`() {
        val beat = t0 + 10.minutes
        assertEquals(beat, session().withHeartbeatAt(beat).heartbeat)
    }

    @Test
    fun `recording and paused sessions are interrupted, others are not`() {
        assertTrue(session().isInterrupted())
        assertTrue(session().apply(RecordingCommand.PAUSE).isInterrupted())
        val stopping = session().apply(RecordingCommand.REQUEST_STOP)
        assertFalse(stopping.isInterrupted())
        assertFalse(stopping.apply(RecordingCommand.FINALIZE).isInterrupted()) // IDLE
    }

    @Test
    fun `a recovery resume opens a new segment on the same activity`() {
        // A session left mid-recording, detected on cold start, resumed into a new segment.
        val interrupted = session().withFixAt(t0 + 5.minutes)
        assertTrue(interrupted.isInterrupted())
        // The in-memory machine moves IDLE -> RECOVERING on detection; here we model the
        // persisted session being taken into RECOVERING and then resumed.
        val resumed = interrupted.copy(state = RecordingState.RECOVERING)
            .apply(RecordingCommand.RECOVER_RESUME)
            .startNewSegment()
        assertEquals(RecordingState.RECORDING, resumed.state)
        assertEquals(1, resumed.currentSegmentIndex)
        assertEquals(id, resumed.activityId)
    }
}
