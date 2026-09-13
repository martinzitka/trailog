package io.github.martinzitka.trailog.importer

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.SensorSample
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * One activity as an archive describes it, ready to be written.
 *
 * A boundary type rather than `:core`'s [io.github.martinzitka.trailog.core.model.Activity], because
 * an import carries two things that domain type has no room for: the **notes**, which only the
 * database holds, and an explicit **start time**, which matters for the activities that have no fix
 * to derive one from.
 *
 * The package is `importer`, never `import` — that is a reserved keyword and illegal as a package
 * segment (CLAUDE.md), the same reason the CLI module is named as it is.
 *
 * @property id taken from the file when it carries one, so re-importing the same archive updates
 *   nothing rather than duplicating the history. Minted fresh only for a file that has no identity.
 * @property startTime the first fix's time, or the document's `<metadata><time>` when there are no
 *   fixes at all.
 */
data class ImportedActivity(
    val id: UUID,
    val type: ActivityType,
    val name: String,
    val notes: String?,
    val startTime: Instant,
    val points: List<RawPoint>,
    val samples: List<SensorSample>,
)

/**
 * What one import run did. Every entry is accounted for in exactly one of these, so the numbers
 * always add up to what the archive held — a silent discrepancy is how a partial import gets
 * mistaken for a complete one.
 *
 * @property imported activities written for the first time.
 * @property skipped entries whose activity was already present. The expected outcome of a re-run,
 *   not a problem.
 * @property failed entries that could not be read. One unreadable file must not cost the other
 *   fifteen hundred, so these are counted and stepped over rather than aborting the run.
 */
data class ImportReport(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = imported + skipped + failed
}
