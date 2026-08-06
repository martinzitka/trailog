package io.github.martinzitka.trailog.core.recording

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * What to do with an interrupted recording session, decided from how long the gap since the
 * last recorded fix is. This is the gap-based recovery policy from CLAUDE.md.
 *
 * The asymmetry that shapes the thresholds: raw data is preserved and a wrongly-resumed tail
 * can always be trimmed, whereas a wrongly-abandoned ride is lost forever. So the policy
 * favours resuming for short gaps and only finalises automatically once a gap is long enough
 * that the ride is almost certainly over.
 */
sealed interface RecoveryDecision {
    /**
     * Gap under [RecoveryPolicy.AUTO_RESUME_MAX]: resume automatically into a **new** segment,
     * then notify the user it happened.
     */
    data object ResumeIntoNewSegment : RecoveryDecision

    /**
     * Gap between [RecoveryPolicy.AUTO_RESUME_MAX] and [RecoveryPolicy.FINALIZE_MIN]: notify
     * with Resume / Finish actions and do nothing until the user answers.
     */
    data object PromptUser : RecoveryDecision

    /**
     * Gap over [RecoveryPolicy.FINALIZE_MIN]: finalise the activity automatically, then notify.
     */
    data object FinalizeAutomatically : RecoveryDecision
}

/**
 * Maps a gap length to a [RecoveryDecision]. The thresholds live here as named constants in
 * one place (CLAUDE.md: not scattered magic numbers) so a future settings screen or
 * per-activity-type tuning has a single source to change.
 */
object RecoveryPolicy {

    /** Gaps shorter than this resume automatically. CLAUDE.md: "under ~15 min". */
    val AUTO_RESUME_MAX: Duration = 15.minutes

    /** Gaps this long or longer finalise automatically. CLAUDE.md: "over ~3 h". */
    val FINALIZE_MIN: Duration = 3.hours

    /**
     * The recovery decision for a [gap] since the last recorded fix.
     *
     * Boundaries: `[0, 15min)` resumes, `[15min, 3h)` prompts, `[3h, ∞)` finalises. A negative
     * gap — which a jumping device clock (CLAUDE.md) can produce — is treated as zero, i.e. a
     * short gap, erring towards preserving the ride rather than abandoning it.
     */
    fun decide(gap: Duration): RecoveryDecision {
        val g = gap.coerceAtLeast(Duration.ZERO)
        return when {
            g < AUTO_RESUME_MAX -> RecoveryDecision.ResumeIntoNewSegment
            g < FINALIZE_MIN -> RecoveryDecision.PromptUser
            else -> RecoveryDecision.FinalizeAutomatically
        }
    }

    /**
     * Convenience: the decision for an interrupted session whose last recorded fix was at
     * [lastFixTime], evaluated at [now]. If no fix was ever recorded ([lastFixTime] is null)
     * the session recorded nothing and there is nothing to interpolate across, so it resumes.
     */
    fun decide(lastFixTime: Instant?, now: Instant): RecoveryDecision =
        if (lastFixTime == null) {
            RecoveryDecision.ResumeIntoNewSegment
        } else {
            decide(now - lastFixTime)
        }
}
