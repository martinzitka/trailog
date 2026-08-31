package io.github.martinzitka.trailog.core.model

/**
 * The kinds of activity Trailog records. Pause thresholds and some statistics differ per
 * type (walking is not cycling — see CLAUDE.md), so the type is part of the domain model,
 * not a display label.
 *
 * **Enum values are persisted by name**, so adding one needs no migration and renaming one would
 * orphan every row that used it. Add freely; never rename or remove.
 *
 * The set past the first five exists because a twelve-year Sports Tracker history contains them
 * (see M2.2 in the plan). They are not speculative: every one below is backed by real recorded
 * activities, which is also why there is no `ROWING` or `CLIMBING` — nothing in the data needed
 * them, and a type nobody has used is a picker entry that earns nothing.
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

    // --- added 2026-08-31 for the Sports Tracker migration ---

    /** Classic and skate. The ceiling covers descents on skis, which are faster than they feel. */
    CROSS_COUNTRY_SKIING(0.5, 12.0),   // ~43 km/h

    /**
     * Piste skiing. The most generous ceiling here by a wide margin: recreational skiers reach
     * 80–90 km/h routinely, and the imported history contains a lift-served day whose raw fixes
     * spike to a reported 1,667 km/h. Both facts matter — the threshold has to pass the first
     * without admitting the second.
     */
    DOWNHILL_SKIING(0.5, 36.0),        // ~130 km/h

    /**
     * Open water and pool. The ceiling is far above human swimming speed on purpose: GPS is
     * unreliable at the surface, and these are frequently entered by hand with no track at all.
     */
    SWIMMING(0.2, 3.0),                // ~11 km/h

    /** Canoe and kayak, including river trips where the current does most of the work. */
    CANOEING(0.2, 6.0),                // ~22 km/h

    /** Whitewater. Slower sustained speed than flatwater paddling, punctuated by fast drops. */
    RAFTING(0.2, 8.0),                 // ~29 km/h

    INLINE_SKATING(0.5, 15.0),         // ~54 km/h
    ICE_SKATING(0.5, 16.0),            // ~58 km/h

    /**
     * Kite-powered travel on snow. Genuinely fast — the imported Hardangervidda sessions average
     * 19–25 km/h across 45–81 km days, with peaks around 54 km/h.
     */
    SNOWKITING(0.5, 25.0),             // ~90 km/h

    /** Summer and winter toboggan runs. Short, steep and quick. */
    BOBSLEIGH(0.5, 30.0),              // ~108 km/h

    /**
     * Anything with no better home. Deliberately last, and deliberately loose: a catch-all with
     * tight thresholds would silently discard real movement it was never tuned for.
     */
    OTHER(0.3, 30.0),
}
