package io.github.martinzitka.trailog.data

/**
 * The two things a bulk operation needs about an activity before deciding to open it: which one it
 * is, and when it happened.
 *
 * A projection rather than a whole [ActivityEntity] because the export walks the entire history —
 * hundreds of rides — and its whole point is to touch one ride's raw points at a time. Reading the
 * full rows first would be harmless today and wrong at 500 activities.
 *
 * @property startTime epoch milliseconds of the activity's first fix, as recorded — never the
 *   device clock at export time.
 */
data class ActivityRef(
    val id: String,
    val startTime: Long,
)
