package io.github.martinzitka.trailog.core.model

/**
 * The kinds of activity Trailog records. Pause thresholds and some statistics differ per
 * type (walking is not cycling — see CLAUDE.md), so the type is part of the domain model,
 * not a display label.
 *
 * @property movingSpeedThreshold speed in metres per second below which a stretch counts as
 *   stopped rather than moving, used to separate moving time from elapsed time. Provisional
 *   values; auto-pause tuning is a backlog item. Kept here so the numbers live in one place
 *   rather than scattered as magic constants.
 */
enum class ActivityType(val movingSpeedThreshold: Double) {
    CYCLING(0.8),
    MOUNTAIN_BIKING(0.8),
    RUNNING(0.5),
    HIKING(0.3),
    WALKING(0.3),
}
