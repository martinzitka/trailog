package io.github.martinzitka.trailog.ui.format

/**
 * Which units the user wants to *read*. A display concern and nothing more.
 *
 * Everything stored, computed and transmitted stays SI regardless of this value — metres, seconds,
 * metres per second, Pascals (CLAUDE.md: "SI everywhere internally. No exceptions."). This enum is
 * consumed by exactly one thing, [Formatter], which is the single point where an SI number becomes
 * a human string. If a second consumer ever appears, that is the bug.
 */
enum class UnitSystem {
    /** Kilometres, metres, km/h, hectopascals. The default. */
    METRIC,

    /** Miles, feet, mph, inches of mercury. */
    IMPERIAL,
}
