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

### Downsampling preserves the envelope

A series is reduced to at most ~800 samples before it reaches the screen. The reduction cuts
each segment into buckets and emits **each bucket's minimum and maximum**, in order.

Plain stride sampling was rejected: it clips peaks, so the summit of a climb or the top of a
sprint disappears whenever it falls between strides — the chart would then contradict the
"max speed" and "elevation gain" figures next to it. The envelope method keeps every extreme
as an emitted sample at a fraction of the cost.

This is a **rendering** concern only. No statistic is ever computed from a downsampled series;
`Statistics` always reads every raw point.

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
