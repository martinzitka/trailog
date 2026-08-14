package io.github.martinzitka.trailog.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingStateMachineTest {

    /** The full set of legal transitions, kept here as the specification the machine must meet. */
    private val legal: Map<Pair<RecordingState, RecordingCommand>, RecordingState> = mapOf(
        (RecordingState.IDLE to RecordingCommand.START) to RecordingState.RECORDING,
        (RecordingState.IDLE to RecordingCommand.DETECT_INTERRUPTED) to RecordingState.RECOVERING,
        (RecordingState.RECORDING to RecordingCommand.PAUSE) to RecordingState.PAUSED,
        (RecordingState.RECORDING to RecordingCommand.REQUEST_STOP) to RecordingState.STOPPING,
        (RecordingState.PAUSED to RecordingCommand.RESUME) to RecordingState.RECORDING,
        (RecordingState.PAUSED to RecordingCommand.REQUEST_STOP) to RecordingState.STOPPING,
        (RecordingState.STOPPING to RecordingCommand.FINALIZE) to RecordingState.IDLE,
        (RecordingState.RECOVERING to RecordingCommand.RECOVER_RESUME) to RecordingState.RECORDING,
        (RecordingState.RECOVERING to RecordingCommand.FINALIZE) to RecordingState.IDLE,
    )

    @Test
    fun `every legal transition yields the specified next state`() {
        for ((input, expected) in legal) {
            val (from, command) = input
            assertEquals(expected, RecordingStateMachine.next(from, command), "from $from via $command")
            assertTrue(RecordingStateMachine.canApply(from, command))
        }
    }

    @Test
    fun `every state-command pair not in the spec is rejected`() {
        for (from in RecordingState.entries) {
            for (command in RecordingCommand.entries) {
                if ((from to command) in legal) continue
                assertNull(RecordingStateMachine.nextOrNull(from, command), "expected $from via $command illegal")
                assertFalse(RecordingStateMachine.canApply(from, command))
                assertFailsWith<IllegalRecordingTransition> {
                    RecordingStateMachine.next(from, command)
                }
            }
        }
    }

    @Test
    fun `a full record-pause-resume-stop-finalize cycle walks back to idle`() {
        var state = RecordingState.IDLE
        state = RecordingStateMachine.next(state, RecordingCommand.START)
        assertEquals(RecordingState.RECORDING, state)
        state = RecordingStateMachine.next(state, RecordingCommand.PAUSE)
        assertEquals(RecordingState.PAUSED, state)
        state = RecordingStateMachine.next(state, RecordingCommand.RESUME)
        assertEquals(RecordingState.RECORDING, state)
        state = RecordingStateMachine.next(state, RecordingCommand.REQUEST_STOP)
        assertEquals(RecordingState.STOPPING, state)
        state = RecordingStateMachine.next(state, RecordingCommand.FINALIZE)
        assertEquals(RecordingState.IDLE, state)
    }

    @Test
    fun `interrupted session recovers by resuming into recording`() {
        var state = RecordingStateMachine.next(RecordingState.IDLE, RecordingCommand.DETECT_INTERRUPTED)
        assertEquals(RecordingState.RECOVERING, state)
        state = RecordingStateMachine.next(state, RecordingCommand.RECOVER_RESUME)
        assertEquals(RecordingState.RECORDING, state)
    }

    @Test
    fun `interrupted session can be finalized without resuming`() {
        val state = RecordingStateMachine.next(RecordingState.RECOVERING, RecordingCommand.FINALIZE)
        assertEquals(RecordingState.IDLE, state)
    }

    @Test
    fun `the exception carries the offending state and command`() {
        val ex = assertFailsWith<IllegalRecordingTransition> {
            RecordingStateMachine.next(RecordingState.IDLE, RecordingCommand.PAUSE)
        }
        assertEquals(RecordingState.IDLE, ex.from)
        assertEquals(RecordingCommand.PAUSE, ex.command)
    }
}
