# 12. Chart profiles are a `:core` view; deleting an activity deletes its raw points

Date: 2026-08-16

## Status

Accepted

## Context

The M1.5 Activity detail screen needs three things the project had no position on:

1. Elevation and speed **charts** that render a four-hour activity (~14,400 fixes at 1 Hz)
   without jank, and that show a recording gap as a break rather than a straight line.
2. A rule for what a **delete** removes, given that CLAUDE.md's first non-negotiable
   principle is "raw points are immutable and sacred … never modified or deleted".
3. **FIT export**, which the screen's acceptance criteria ask for alongside GPX.

## Decision

### Chart series live in `:core`, not in the ViewModel

`TrackProfile` in `:core/stats` produces `ProfileSeries` — one polyline per segment, of
`(cumulative distance, value)` samples. The Android screen only draws what it is handed.

- **Segments stay separate all the way to the `Path`.** A `ProfileSeries` is a list of
  per-segment sample lists, and `ProfileChart` draws each as its own `Path`. There is no
  point in the pipeline where the two sides of a gap could be joined.
- **The x-axis is cumulative distance, which does not advance across a gap.** A gap therefore
  shows as a vertical discontinuity, not a horizontal one. That is the honest picture: no
  distance was covered. A time axis would have made the gap wider on screen but would have
  disagreed with every distance figure on the same page.
- **The plotted series reuses the statistics pipeline's own smoothing.** Altitude is plotted
  after the same centered moving average `Elevation` accumulates gain over, and speed is
  derived from the same in-segment hops as `Statistics.maxSpeed`. A chart smoothed a second,
  slightly different way would visibly disagree with the summary figures printed above it.
- **Speed comes from hops, not from the OS-reported per-fix speed.** The reported value is
  absent on some fixes and derived differently by different providers, which would make the
  chart's shape depend on the handset.

### Downsampling: buckets divide the distance axis, and collapse differently per quantity

A series is reduced to at most ~800 samples before it reaches the screen. Three decisions
inside that, all of which were **corrected by testing against real rides on the device** — the
first version of each looked fine in unit tests and was unusable on a real 67 km recording.

**Buckets divide the distance axis, not the sample list.** The x-axis is distance, but fixes
arrive on a clock. A rider stopped at a junction for ten minutes produces ~600 fixes occupying
almost no x-width — and because GPS wanders while stationary, occupying a *little*. Bucketing
by sample index handed each standstill a large share of the budget crammed into a few pixels,
drawn as a dense vertical stripe. Bucketing by distance gives a standstill the one bucket its
distance earns.

**Elevation keeps each bucket's extremes; speed keeps each bucket's mean.** Stride sampling is
wrong for both — it clips peaks, so a summit vanishes whenever it lands between strides. But
an *envelope* is only right where the signal exceeds the noise. Applied to GPS-derived speed it
plotted the noise band: a solid block between a standstill and the fastest wobble, with no
trend visible. Speed therefore averages within the bucket; the peak the reader wants is already
printed as the max-speed figure.

**A smoothing window only helps if it is wider than the chart's output resolution.** This was
the least obvious finding. At 800 samples over a 67 km ride, adjacent plotted samples are ~85 m
— about 15 s — apart, so the original 15-point (15 s) moving average smoothed almost nothing
that survived to the screen. Widening it to 61 points roughly halves the jaggedness of the
drawn line, measured as the mean step between adjacent plotted samples:

| ride | window 15 | window 61 |
|---|---|---|
| 67.6 km / 6h45 | 3.27 km/h | 1.77 km/h |
| 38.5 km / 3h45 | 2.10 km/h | 0.97 km/h |
| 14.3 km / 39 min | 0.91 km/h | 0.36 km/h |

Coarsening the sample budget instead was measured and rejected: it makes the line *more*
jagged, because each remaining step then spans more ground.

This is all a **rendering** concern. No statistic is ever computed from a downsampled series;
`Statistics` always reads every raw point.

A consequence worth stating: the smoothed speed curve peaks below the summary's max-speed
figure (32 km/h against 69 km/h on the reference ride). Both are honest — one is a minute-scale
trend, the other an instantaneous maximum over two fixes — but the max-speed statistic is
itself unfiltered and CLAUDE.md already flags it as provisional until the filtering pipeline
lands. Revisit the pairing then.

### Deleting an activity deletes its raw points

`ActivityRepository.delete` removes the activity row, its cached statistics and **every raw
point of that activity**, in one transaction.

"Raw points are immutable and sacred" binds *the app*, not the person whose location history
it is. Nothing automatic may reach this path — no filter, no recompute, no migration, no
recovery. It runs only from an explicit, confirmed user delete on the detail screen.

Keeping the fixes of a ride the user deleted would mean holding coordinates they believe are
erased. That is the opposite of this project's stated purpose, and "delete everything in it"
is a product feature here (CLAUDE.md's legal note), not merely a GDPR obligation.

`raw_points` carries no foreign key to `activities` — points predate that table and survive
independently of it — so the delete is explicit rather than a cascade, and points go first so
no window exists where the activity is gone but its coordinates remain.

### FIT export is deferred; GPX ships

The detail screen exports GPX through `:core`'s single `Gpx` writer, to a destination chosen
via the system document picker, entirely offline.

**FIT export is not implemented**, because `:core` has no FIT codec yet — M1.2 lists FIT
reading and writing but only GPX landed. FIT is a binary format (definition messages, a
developer-data profile, CRCs) and encoding it either means hand-rolling an encoder in `:core`
or adding a dependency to `:core`, which CLAUDE.md says must be asked about first. Shipping a
disabled or stubbed FIT button would have concealed unfinished work, so there is no FIT
affordance on the screen at all.

## Consequences

- The linked-charts item (M1.5 screen 6) has the data structure it needs: touching a chart
  maps an x back to a cumulative distance, and the map can resolve that to a point. Nothing
  about `ProfileSeries` has to change to add it.
- Chart tuning (smoothing windows, sample budget) happens in `:core` with unit tests, not by
  eyeballing a Composable.
- A user who wants to keep a deleted ride must export it first. The delete confirmation says
  so explicitly.
- When the FIT codec lands in `:core`, the export action becomes a two-item menu; the screen's
  export plumbing (build a document, write it to a picked URI) already generalises.
- When M1.6 replaces `RouteMap`'s body with MapLibre, none of this changes: the detail screen
  depends on the `RouteMap` signature, and the charts do not involve the map at all.
