# 10. Persistence: the derived-stats cache, a non-destructive backfilling migration, and JVM migration tests

Date: 2026-08-15

## Status

Accepted

## Context

M1.4 adds the durable persistence layer on top of M1.3's `raw_points` and
`recording_sessions`: an `activities` metadata table, a derived `activity_stats` cache, an
exported-and-committed schema, tested migrations, and a "recompute all derived data from raw
points" path (IMPLEMENTATION_PLAN.md).

Three things about the environment shape the decisions:

- **Raw points are immutable and sacred** (CLAUDE.md). Every statistic is a recomputable view
  over them, so anything derived is disposable and must be rebuildable.
- **The developer's device already holds real, irreplaceable recordings** — six field rides
  under the M1.3 version-1 schema. A schema change must not risk them; a destructive
  fallback is not an option.
- **CI runs build + unit tests + lint with no emulator.** Room's standard
  `MigrationTestHelper` is an instrumentation API that needs a device, so migration tests
  written the usual way would not run on CI.

## Decision

**`activity_stats` is a disposable cache, one row per activity, keyed by the activity id, with
a `CASCADE` foreign key to `activities`.** It mirrors `:core`'s `ActivityStats` field for
field. Durations are stored as whole milliseconds (`INTEGER`); the sub-millisecond loss is
irrelevant to any displayed figure and the row can always be rebuilt. A `computedAt` column
records when it was last rebuilt, so staleness after an algorithm change is detectable. The
cache is never a source of truth — dropping the whole table loses nothing recoverable.

**The recompute path lives in `ActivityRepository` and goes through the single `:core`
`Statistics` implementation.** `recompute(id)` reads an activity's raw points in time order,
lets `:core` group them into segments, computes stats, and overwrites the cache row.
`recomputeAll()` sweeps every activity. Because segmentation happens in `:core`, the result is
fully segment-aware: nothing is interpolated across a gap between segments.

**Migration 1 → 2 is non-destructive and backfills pre-existing recordings.** It creates the
two new tables and leaves `raw_points` and `recording_sessions` untouched, so every fix
recorded under v1 survives. It then inserts an `activities` row for every distinct
`activityId` already present in `raw_points`, taking the type from a still-live recording
session where one exists and otherwise defaulting to `CYCLING` (editable metadata the user can
correct later), with `startTime` set to the earliest fix. Without this backfill the field
recordings would be orphaned — points with no activity row, invisible to the recompute sweep
and to History. No destructive fallback (`fallbackToDestructiveMigration`) is ever configured.

**Migration tests run on the JVM under Robolectric, not via `MigrationTestHelper`.** The test
builds the version-1 database by hand from the M1.3 schema (unchanged in v2) and opens it
through Room with the real migration. Room's own post-migration schema validation still fires
— an incorrect migration throws on open — so the validation guarantee is preserved without an
emulator. Robolectric drives real (native) SQLite, so constraints and the migration SQL
actually execute. The recompute path is tested the same way against an in-memory database.

**The recording engine writes the activity row at record start and fills the stats cache at
finalise.** `start()` inserts the `activities` row immediately (empty name; a display name is
derived at the render edge). `finalize()` and the automatic-finalise recovery branch call
`recompute` once the activity is complete. These additions are off the durability-critical
raw-point write path, which is unchanged and field-proven.

## Consequences

- History and detail screens (M1.5) read cached stats instead of re-running the pipeline on
  every open, while the cache stays honest because it is only ever produced by recomputation
  from raw points.
- The "improve an algorithm, rebuild every number" workflow the sacred-raw-points rule exists
  for is real and tested: change `:core`, call `recomputeAll()`, done.
- The schema is committed under `app/schemas/`. There is **no** `1.json`, because version 1
  predated schema export; this is why the migration test reconstructs the v1 schema by hand
  rather than loading it. Future versions export normally and can use either approach.
- Activity type for backfilled field recordings is a guess (`CYCLING`) where no session
  survived. It is mutable metadata, corrected in one tap once the History/detail edit UI
  exists; it never touches the raw points.
- Committing the on-device upgrade still needs a real-device check before it is trusted with
  the developer's actual recordings: the migration is proven in tests and on native SQLite,
  but the roadmap's rule is that persistence changes touching the real database are verified
  on hardware.
