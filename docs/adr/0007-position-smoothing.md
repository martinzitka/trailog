# 7. Position smoothing reuses RawPoint as an in-memory derived view, and is opt-in

Date: 2026-08-05

## Status

Accepted

## Context

The M1.2 filtering pipeline is specified as "accuracy threshold, speed plausibility rejection,
smoothing" (IMPLEMENTATION_PLAN.md). Accuracy and speed filters only *select* raw points, so
their output is still fixes exactly as the OS delivered. Position smoothing is different: it
*moves* coordinates (a moving average over lat/lon) to stop GPS wander from inflating distance.

Two design questions follow:

1. **What type carries a smoothed point?** `RawPoint` is documented as immutable and sacred —
   persisted exactly as delivered. A smoothed coordinate is not that. A dedicated
   smoothed-geometry type would be the purest model, but `Segment`, `Statistics`, `splits` and
   the GPX layer are all built on `RawPoint`; threading a second geometry type through them
   (generics or duplication) is significant surface area.

2. **Should smoothing be on by default?** The first real reference ride (clean, sub-3 m accuracy)
   already computed distance to within 0.05% of the published figure with *no* position
   smoothing — the filter was a no-op. Smoothing only helps *noisy* tracks (tree cover, urban
   canyon, standing still), and there is no noisy reference track in the fixture set yet to tune
   the window against or to prove it helps rather than harms.

## Decision

- **Reuse `RawPoint` as the carrier for smoothed output**, as an explicitly in-memory derived
  view. `PositionSmoother` returns new `Segment`/`RawPoint` instances; it never mutates input and
  its output must never be written back to storage. Only latitude and longitude change; altitude,
  time, accuracy, segment index and the rest pass through unchanged. A dedicated smoothed-geometry
  type is deferred until something needs to distinguish the two at compile time — reconsider when
  chart geometry or map rendering wants a separate simplified/smoothed path.
- **Smoothing is opt-in, not in `Filters.default`.** `Filters.default` stays accuracy + speed
  (validated as a safe near-no-op on clean data). `Filters.smoothed(type, window)` adds the
  smoother as a final step. Enabling smoothing by default waits for a noisy reference track to
  tune and validate the window; the default window (5) is provisional.
- Smoothing is **segment-aware**: the moving average shrinks at each segment's ends and never
  reaches across a boundary, so a gap is never bridged — consistent with every other statistic.

## Consequences

- No refactor of the statistics layer; `Filters.smoothed` composes exactly like the other
  strategies. The cost is that a smoothed `RawPoint` carries an `accuracy`/`speed` that describe
  its underlying raw fix, not the smoothed position — acceptable because distance and the other
  consumers do not read those fields off smoothed points.
- Because smoothing is opt-in, historical distances do not change from this ADR; enabling it
  later (a one-line call-site change) is the point at which a recompute + `activity_stats` cache
  invalidation (M1.4) would apply.
- The purity concern ("sacred RawPoint reused for derived data") is real but contained: the type
  is reused only transiently, in-memory, immediately before statistics, never persisted.
