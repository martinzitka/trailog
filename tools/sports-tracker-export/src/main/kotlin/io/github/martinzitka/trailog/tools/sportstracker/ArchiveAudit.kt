package io.github.martinzitka.trailog.tools.sportstracker

/** What an audit concluded about one workout in the downloaded archive. */
enum class AuditVerdict {
    /** A track is present and its size is consistent with the distance the API reported. */
    OK,

    /** The index names this workout but no file was downloaded. */
    MISSING,

    /**
     * No track points, and none were ever expected: the workout was typed in by hand rather than
     * recorded. Not a failure. Its distance and duration live in the workout list, which is why
     * that response is kept verbatim.
     */
    EMPTY_AS_EXPECTED,

    /**
     * No track points at all, for a workout that was genuinely recorded. This is data loss —
     * upstream, not ours — and it is invisible without this check, because an empty GPX is
     * perfectly valid GPX.
     */
    EMPTY_BUT_TRACK_EXPECTED,

    /**
     * Far too few points for the distance covered. The export is technically valid GPX and the
     * downloader accepted it, which is exactly why this check exists.
     */
    TOO_FEW_POINTS,
}

/** One workout's audit result. */
data class WorkoutAudit(
    val key: String,
    val expectedDistance: Double?,
    val trackPoints: Int?,
    val verdict: AuditVerdict,
) {
    val isProblem: Boolean
        get() = verdict != AuditVerdict.OK && verdict != AuditVerdict.EMPTY_AS_EXPECTED
}

/**
 * Checks a downloaded archive against the index that drove it.
 *
 * Exists because "the download succeeded" and "the data arrived" turned out to be different
 * claims. Sports Tracker returned valid GPX containing a *single* track point for rides of 41 to
 * 49 km, and the downloader accepted every one of them — it verified the body was GPX, which it
 * was. Format validity says nothing about content.
 *
 * The rule is deliberately a loose sanity bound rather than a tuned threshold, because the point
 * is to catch collapse, not to grade quality. Real exports here run one point every 10 to 20
 * metres; requiring one per [METRES_PER_EXPECTED_POINT] leaves two orders of magnitude of headroom
 * and still catches a 49 km ride delivered as one point.
 */
object ArchiveAudit {

    /**
     * A track is suspect below one point per this many metres. Generous by design: a genuinely
     * sparse but real recording must not be reported as damaged.
     */
    const val METRES_PER_EXPECTED_POINT: Double = 500.0

    /** Workouts shorter than this are not size-checked — too little distance to reason about. */
    private const val MIN_DISTANCE_TO_CHECK = 200.0

    /**
     * Classifies one workout.
     *
     * @param manuallyAdded whether the user typed the workout in instead of recording it. This,
     *   not distance, is what decides whether an empty export is expected — a hand-entered swim
     *   carries a distance and no track, and so does a ride whose track was lost.
     * @param expectedDistance the distance the API reported, in metres, or null if it said nothing.
     * @param trackPoints points found in the downloaded file, or null if no file exists.
     */
    fun classify(
        manuallyAdded: Boolean,
        expectedDistance: Double?,
        trackPoints: Int?,
    ): AuditVerdict {
        if (manuallyAdded) {
            // Nothing to expect and nothing to check: there was never a recording behind it.
            return if (trackPoints == null || trackPoints == 0) {
                AuditVerdict.EMPTY_AS_EXPECTED
            } else {
                AuditVerdict.OK
            }
        }
        if (trackPoints == null) return AuditVerdict.MISSING
        if (trackPoints == 0) return AuditVerdict.EMPTY_BUT_TRACK_EXPECTED

        val distance = expectedDistance ?: 0.0
        if (distance < MIN_DISTANCE_TO_CHECK) return AuditVerdict.OK

        val expected = distance / METRES_PER_EXPECTED_POINT
        return if (trackPoints < expected) AuditVerdict.TOO_FEW_POINTS else AuditVerdict.OK
    }

    /** Renders a human-readable report. Counts and keys only — never a coordinate. */
    fun report(audits: List<WorkoutAudit>): String = buildString {
        val byVerdict = audits.groupBy { it.verdict }
        appendLine("Archive audit — ${audits.size} workouts in the index")
        appendLine()
        AuditVerdict.entries.forEach { verdict ->
            appendLine("  ${verdict.name.padEnd(26)} ${byVerdict[verdict].orEmpty().size}")
        }

        val problems = audits.filter { it.isProblem }
        appendLine()
        if (problems.isEmpty()) {
            appendLine("No problems. Every workout that should have a track has one.")
            return@buildString
        }

        appendLine("${problems.size} workout(s) need attention:")
        problems.sortedBy { it.verdict }.forEach { audit ->
            val distance = audit.expectedDistance?.let { "%.2f km".format(it / 1000) } ?: "unknown"
            appendLine(
                "  ${audit.key}  ${audit.verdict.name.padEnd(26)} " +
                    "reported $distance, ${audit.trackPoints ?: "no"} track points",
            )
        }
    }
}
