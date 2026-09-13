# 24. The import archive is a zip of GPX, and imported ids are derived

Date: 2026-09-13

## Status

Accepted

## Context

M2.2 has to get a long Sports Tracker history — years of workouts, millions of track points — into
Trailog. The plan left one thing open because it depends on when it is done: *"Before M3
exists: writes directly to a local store or emits app-importable output. After M3 exists:
authenticates and uploads via the sync API as an ordinary client."*

M3 does not exist, so the importer has nowhere to put anything. Three ways were considered:

1. **The CLI writes the app's SQLite database directly.** Fastest to get data onto the phone, and
   wrong: the CLI is JVM and Room is Android, so it would hand-write Room's schema — a second
   definition of the schema that drifts from the migrations, plus Room's identity-hash check to
   satisfy on open. A second schema owner is exactly the sort of duplication `:core` exists to
   prevent.
2. **Wait for M3** and import server-side, which is the importer's designed end state. It also
   defers the history behind a whole backend.
3. **The CLI emits an archive the app imports.** One schema owner — Room, in `:app` — and no
   dependency on the backend.

## Decision

**The importer emits a zip of GPX, in the same shape the app's own bulk export already writes
(ADR 0020), and the app imports that zip.**

The symmetry is the point, not a coincidence. Migration and restore-from-backup become the *same
operation*, so there is one import path to build and test rather than two, and the archive the user
already exports every so often becomes a restorable backup rather than a hopeful one. CLAUDE.md's
deployment guidance says a backup that has never been restored is a guess; this is what closes that
loop.

### Imported activity ids are derived, not minted

An imported activity's id is **UUIDv5 over the Sports Tracker workout key**, under a fixed
namespace, written into the file as `<trailog:activityId>` in the track's `<extensions>`.

Trailog's own recordings keep UUIDv7 — an activity must be creatable offline with a time-ordered id
— but an imported activity already has a stable identity upstream, and deriving from it is what
makes the import **idempotent**: a second run recognises every ride it already wrote instead of
duplicating the lot. That matters because the type mapping is hand-written and will be wrong
somewhere; re-running after a correction has to be cheap, or the mapping cannot be iterated on.

Carrying the id *in the file* rather than in a side-car manifest keeps each GPX self-contained, so a
single file dropped in behaves the same as one inside an archive.

### `<metadata><time>` carries the date a track cannot

A small number of workouts have no usable track: some typed in by hand, and some whose geometry was
lost upstream. They have no fix to read a date from, and without one they imported undated. The activity's date
goes in `<metadata><time>` — standard GPX, understood by other tools, and the natural home for a
document-level timestamp.

### Source summaries go in the notes, quoted

A track-less activity computes as zero, because Trailog recomputes every statistic from raw points.
The source's own distance and duration **cannot** go into the statistics cache: that cache is a
recomputable view over raw data by definition, and a third-party figure Trailog can never reproduce
or verify does not belong in it (the same line ADR 0023 draws for imported avg/max heart rate).

They go into the activity's notes instead, plainly attributed — *"Imported from Sports Tracker,
which reported 1.90 km, 25 min moving. Entered by hand, so it never had a track."* — so nobody later
mistakes them for something Trailog measured. The note also records **which** case it is, because
`isManuallyAdded` is the only thing separating "never had a track" from "lost its track" and
distance cannot tell them apart.

The figures are quoted in the units Sports Tracker displayed them in. That is a deliberate exception
to "no formatted string is ever stored" (ADR 0014): this is a quotation of another system's claim,
not a Trailog figure being rendered, and there is nothing to recompute it from if a unit preference
later changes.

## Consequences

- **The source's units were measured, not assumed.** The scraper deliberately left every figure as
  raw text. Calibrating against the real tracks settled them: `totalDistance` is metres (median
  ratio 0.9986 against haversine distance over the actual points), `startTime`/`stopTime` are epoch
  millis, and `totalTime` is **moving** seconds — its ratio to `stopTime - startTime` has a median
  of 0.82 and a maximum of exactly 1.0000 across every workout measured, which nothing but a
  moving-time figure could produce. Moving and elapsed therefore land on the right fields instead of being
  conflated.
- The archive runs to hundreds of megabytes of GPX. The app's import has to stream it one ride at a
  time; holding it is not an option.
- `:tools:importer` deliberately **does not depend on** `:tools:sports-tracker-export`, duplicating
  its tolerant JSON tree-walking instead. That module is throwaway and gets deleted when the
  migration is done; a durable tool must not have a hole punched in it when that happens.
- Producer-specific corrections — the Sports Tracker `<name>`/`<desc>` inversion — live in the
  importer and are gated on the `creator` string. `:core` parses both fields faithfully and applies
  no correction, because a quirk fix buried in the shared parser would corrupt files from every
  other tool.
- A single-point export is treated as track-less for the purpose of the note, but **the point is
  still stored**. Raw points are never filtered on ingest.
