# 23. Sensor samples are a stream of their own, and each type declares its own unit

Date: 2026-09-13

## Status

Accepted

## Context

Some of the exported Sports Tracker workouts carry per-point heart rate, and the importer (M2.2)
has to write complete rows on its first pass. Importing first and backfilling later is worse than it
sounds: the importer deduplicates, so a re-run *skips* an existing activity rather than filling in
what was missing, and by then hundreds of imported activities will have been renamed and retyped by
hand. That is why M2.3's foundation slice was scheduled ahead of the import CLI.

Two questions had to be answered before any code was written.

**Where do readings live?** The obvious answer is more nullable columns on `raw_points` — a
`heartRate` beside `pressure`. It is wrong. A column there can only hold a reading that happened to
coincide with a location fix, so nothing is recorded during a signal blackout, a tunnel, or a pause,
while a chest strap emits at about 1 Hz regardless of what the GPS is doing. It also forces every
reading to adopt a fix's timestamp, which is a lie about when the reading was taken.

**What unit?** CLAUDE.md requires SI internally with no exceptions, and heart rate in SI is hertz.

## Decision

### A separate timestamped stream

`SensorSample(time, type, value)` in `:core`, and a `sensor_samples` table in `:app` (activity id,
time, type, value) added by the non-destructive 2 → 3 migration. Sensor data shares the track's
clock and nothing else.

- **Generic `type` + `value`, not a column per sensor.** A new sensor is a new `SensorType`
  constant, never a migration. This matches the criterion the Sensors screen already meets.
- **Samples are raw data.** Written as they arrive, never smoothed on ingest, never edited. Like
  fixes, they are assumed to arrive out of order and duplicated, so there is deliberately no
  uniqueness constraint and `SensorStream.normalise` de-duplicates on read. Averages and chart lines
  are recomputable views.
- **Alignment onto segments is by timestamp and never crosses a boundary.** A reading taken in the
  gap between two segments belongs to neither and is omitted from `SensorStream.bySegment` rather
  than being attributed to whichever is nearest.
- **Deletion cascades from `activities` by foreign key**, as `activity_stats` already does.
  `raw_points` is explicit instead only because it predates that table.

### Each `SensorType` declares its own unit

`SensorUnit` is a symbol on the enum, not a string: beats per minute, revolutions per minute, watts,
degrees Celsius. SI where a meaningful SI unit exists; the domain's unit where SI would be perverse.

This is not a loophole in the SI rule, it is the rule's own purpose. BLE reports heart rate as an
integer bpm, and 140 bpm in hertz is 2.333…, which does not round-trip — 139.99999 comes back.
Storing hertz would *introduce* exactly the precision loss the rule exists to prevent. What the rule
actually buys is that there is exactly one unit per field and that conversion happens only at the
display edge, and both hold completely: one unit per sensor type, fixed forever, converted nowhere
but `Formatter`. The same reasoning gives temperature degrees Celsius — Kelvin is SI and meaningful,
but nothing produces it, nothing displays it, and the user's unit choice is °C or °F.

Four constants exist: heart rate, cadence, power and temperature. Unlike an `ActivityType` — where a
type nobody has used is a picker entry that earns nothing — a `SensorType` has no user-facing cost,
and these four are exactly what standard GPX expresses per track point. Not having a constant for
one would mean silently dropping a value a file plainly contained. Only heart rate is known to be
present in the imported history, and only heart rate has a producer planned.

### GPX carries the stream twice, deliberately

GPX has no place for a stream that is not bolted to a track point, so the writer emits both:

1. **The exact stream**, in the `<trk>`'s `<extensions>` under `urn:trailog:gpx:v1`, one
   `<trailog:samples type="…">` block per sensor with a `<trailog:s t="…" v="…"/>` per reading. This
   is lossless — every timestamp intact, including readings taken while the GPS had nothing to say.
2. **A lossy projection** onto the nearest track point within three seconds, as `gpxtpx:hr`,
   `gpxtpx:cad`, `gpxtpx:atemp` and `gpxpx:PowerInWatts`. Garmin's namespaces are declared only when
   something is actually written in them.

The reader prefers the exact stream **per sensor type**, so a Trailog file round-trips without
double-counting, and a file that mixes the two loses neither half. A sensor type this build does not
know is skipped rather than rejected, so a file from a later version still opens.

## Consequences

- A GPX with sensor data is roughly twice the size it strictly needs to be. Accepted: CLAUDE.md is
  explicit that data volume is not a problem here, and the alternative is either a file only Trailog
  can read or a round trip that quietly shifts every timestamp onto the nearest fix.
- The integer-typed Garmin elements are written whole (`142`, not `142.0`), because `142.0` there is
  a document strict readers reject. No precision is lost — the sources are integral and the exact
  value is in the Trailog stream regardless.
- The three-second match tolerance is a constant in `SensorStream`, not a scattered magic number. It
  is wide enough to absorb the mismatch between a ~1 Hz strap and a ~1 Hz GPS whose timestamps never
  line up, and narrow enough that a fix in a sparse stretch is not labelled with a stale reading.
- Nothing is backfilled by the migration: no activity recorded before this version carries a reading
  anywhere. Imported activities get theirs from the file they came from, on the first pass.
- **Imported summary figures remain out of scope.** A source app's average or maximum heart rate, or
  a total step count, is a third-party derived figure Trailog can never recompute or verify, which
  cuts directly against "every statistic is a recomputable view over raw data". Per-point samples
  have no such problem; totals do.
