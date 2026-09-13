# 6. Elevation smoothing defaults tuned against a DEM reference

Date: 2026-08-05

## Status

Accepted (provisional defaults)

## Context

Elevation gain is the most-compared and most-wrong tracker statistic (CLAUDE.md). Trailog
computes it by smoothing the altitude series (centered moving average, `smoothingWindow`
points) then accumulating with a deadband (`threshold` metres) — see `Elevation.kt`. Until now
the defaults (window 7, threshold 3 m) were provisional, tuned only against synthetic tests.

The first real reference ride was recorded on a device with **no barometer**, so altitude is
GPS-only and noisy, with two independent elevation references:

- **Sports Tracker** (the app's own export of the same ride): **552 m** ascent. GPS-altitude
  based, lightly smoothed. The developer distrusts it precisely because the phone has no
  barometer.
- **mapy.cz** route planner for the same route: **466 m** ascent, from a terrain DEM. Judged
  the more trustworthy figure, possibly very slightly oversmoothed.

The two references disagree by ~18%, which is normal for elevation (CLAUDE.md notes reference
implementations disagree and warns against chasing noise below the 5% tolerance).

A parameter sweep over the real ride showed the **deadband threshold, not the smoothing
window, is the dominant lever**. With window fixed at 7 points (7 s at 1 Hz):

| threshold | 3 m | 5 m | 6 m | 8 m |
|---|---|---|---|---|
| ascent | 552 | 508 | 495 | 458 |
| vs mapy 466 | +18% | +9% | +6% | −2% |

The naive positive-delta sum (no smoothing, no deadband) was 920 m — 97% inflated — which is
the behaviour the algorithm exists to prevent.

## Decision

- Change the default deadband **`threshold` from 3 m to 6 m**; keep **`smoothingWindow` at 7**.
  On the reference ride this yields ~495 m, sitting between the DEM figure (466) and the
  GPS-based figure (552), and honours the general principle that GPS altitude needs aggressive
  noise suppression before integration.
- Prefer raising the **threshold** over widening the **window**. The window is expressed in
  points (= seconds at 1 Hz); a large window lags real terrain and rounds off short sharp
  climbs, whereas the deadband discards sub-threshold wander directly. A 31-point window was
  rejected for this reason despite happening to match the DEM figure on this one ride.
- These defaults remain **provisional**. Tightening below the current ~6% spread needs DEM-based
  absolute anchoring and/or a barometer-equipped device, plus more reference rides.
- Synthetic elevation unit tests that previously relied on the default now pass an explicit
  `threshold = 3.0`, so they test algorithm mechanics independently of the production default.
  Real-world calibration is covered by the real-fixture test.

## Consequences

- Historical elevation numbers computed with the old default will change (drop ~10% on noisy
  GPS-only tracks). All statistics are recomputable views over raw points (CLAUDE.md), so this
  is a recompute, not data loss — but any cached `activity_stats` (M1.4) must be invalidated and
  recomputed when the default changes.
- The change is one line in `ElevationParams`; callers needing the old behaviour pass explicit
  params. Barometric fusion (a separate M1.2 item) will supersede GPS-only gain on devices that
  have a barometer, and will warrant revisiting these defaults.
