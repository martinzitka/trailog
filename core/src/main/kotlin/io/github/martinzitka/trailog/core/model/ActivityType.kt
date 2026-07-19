package io.github.martinzitka.trailog.core.model

/**
 * The kinds of activity Trailog records. Pause thresholds and some statistics differ per
 * type (walking is not cycling — see CLAUDE.md), so the type is part of the domain model,
 * not a display label.
 */
enum class ActivityType {
    CYCLING,
    MOUNTAIN_BIKING,
    RUNNING,
    HIKING,
    WALKING,
}
