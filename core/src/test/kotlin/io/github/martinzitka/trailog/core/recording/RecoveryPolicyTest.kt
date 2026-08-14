package io.github.martinzitka.trailog.core.recording

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecoveryPolicyTest {

    @Test
    fun `a short gap resumes automatically`() {
        assertEquals(RecoveryDecision.ResumeIntoNewSegment, RecoveryPolicy.decide(30.seconds))
        assertEquals(RecoveryDecision.ResumeIntoNewSegment, RecoveryPolicy.decide(14.minutes))
    }

    @Test
    fun `a medium gap prompts the user`() {
        assertEquals(RecoveryDecision.PromptUser, RecoveryPolicy.decide(16.minutes))
        assertEquals(RecoveryDecision.PromptUser, RecoveryPolicy.decide(2.hours))
    }

    @Test
    fun `a long gap finalizes automatically`() {
        assertEquals(RecoveryDecision.FinalizeAutomatically, RecoveryPolicy.decide(4.hours))
    }

    @Test
    fun `the auto-resume boundary is exclusive - exactly 15 min prompts`() {
        assertEquals(
            RecoveryDecision.ResumeIntoNewSegment,
            RecoveryPolicy.decide(RecoveryPolicy.AUTO_RESUME_MAX - 1.milliseconds),
        )
        assertEquals(RecoveryDecision.PromptUser, RecoveryPolicy.decide(RecoveryPolicy.AUTO_RESUME_MAX))
    }

    @Test
    fun `the finalize boundary is inclusive - exactly 3 h finalizes`() {
        assertEquals(
            RecoveryDecision.PromptUser,
            RecoveryPolicy.decide(RecoveryPolicy.FINALIZE_MIN - 1.milliseconds),
        )
        assertEquals(RecoveryDecision.FinalizeAutomatically, RecoveryPolicy.decide(RecoveryPolicy.FINALIZE_MIN))
    }

    @Test
    fun `a negative gap from a jumping clock is treated as short and resumes`() {
        assertEquals(RecoveryDecision.ResumeIntoNewSegment, RecoveryPolicy.decide((-5).minutes))
    }

    @Test
    fun `decide from timestamps measures the gap since the last fix`() {
        val lastFix = Instant.fromEpochSeconds(1_000_000)
        assertEquals(
            RecoveryDecision.ResumeIntoNewSegment,
            RecoveryPolicy.decide(lastFix, now = lastFix + 5.minutes),
        )
        assertEquals(
            RecoveryDecision.FinalizeAutomatically,
            RecoveryPolicy.decide(lastFix, now = lastFix + 5.hours),
        )
    }

    @Test
    fun `a session with no recorded fix resumes rather than being abandoned`() {
        val now = Instant.fromEpochSeconds(1_000_000)
        assertEquals(RecoveryDecision.ResumeIntoNewSegment, RecoveryPolicy.decide(lastFixTime = null, now = now))
    }
}
