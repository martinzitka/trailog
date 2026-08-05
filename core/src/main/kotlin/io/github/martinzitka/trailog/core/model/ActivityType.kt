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
 * @property maxPlausibleSpeed speed in metres per second above which a jump between two fixes
 *   is treated as GPS noise rather than real movement, used by the speed-plausibility filter.
 *   Generous by design — it rejects teleport spikes, not fast descents. Provisional; final
 *   tuning waits on real reference tracks, like the elevation and moving-time thresholds.
 */
enum class ActivityType(
    val movingSpeedThreshold: Double,
    val maxPlausibleSpeed: Double,
) {
    CYCLING(0.8, 25.0),        // ~90 km/h — covers road descents
    MOUNTAIN_BIKING(0.8, 22.0),
    RUNNING(0.5, 8.0),         // ~29 km/h — beyond world-class sprint pace
    HIKING(0.3, 5.0),
    WALKING(0.3, 4.0),
}
