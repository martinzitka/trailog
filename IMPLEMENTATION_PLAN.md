# Implementation Plan

Sequenced milestones with explicit acceptance criteria. Read `CLAUDE.md` first for the
principles and gotchas that constrain every task here.

**Rule for the whole plan: do not start a milestone until the previous one's acceptance
criteria are met on real hardware.** Building UI while recording is still unproven is the
main way this project fails.

---

## Global acceptance criteria

These apply to **every** milestone and sub-milestone below, in addition to its own
criteria. They are not repeated per section.

- [ ] `./gradlew check` passes — build, lint, and all tests, with no new warnings.
- [ ] Test coverage meets the per-module expectations in `CLAUDE.md` (high bar for
      `:core`; ViewModels but not Composables for `:app`; mandatory security tests for
      `:server`).
- [ ] CI is green on the pushed branch.
- [ ] No secrets, credentials, keystores or tokens added to the repository.
- [ ] Any architectural decision made along the way is recorded as an ADR in `docs/adr/`.
- [ ] Anything left incomplete is stated explicitly in the response, not buried in a TODO.
- [ ] All units are SI internally; no conversion outside the display formatting utility.

M0 is exempt from the coverage and ADR criteria — it is throwaway code and writing tests
for it is waste.

---

## M0 — Recording spike (throwaway)

The only purpose of M0 is to find out whether Android background recording works well
enough to justify the rest of the project. Nothing built here needs to survive. Write it
fast and badly.

**Build**
- A single-Activity app. No architecture, no abstractions, no tests.
- Foreground service with `foregroundServiceType="location"` and a persistent
  notification, requesting fixes at 1 Hz.
- Full permission flow: foreground location, then background location via system settings,
  then `POST_NOTIFICATIONS`, then battery-optimisation exemption.
- Every fix appended to a Room table immediately on arrival — no in-memory buffering.
- Barometer sampled alongside, if present.
- A debug screen: fix count, time since last fix, reported accuracy, satellite count,
  service alive/dead, barometer reading.
- A button that dumps everything to a GPX file in shared storage.

**Acceptance criteria**
- [ ] Two outdoor activities of at least 2 hours each, screen off, phone in a pocket, on
      the developer's actual device.
- [ ] At least one in an area with poor or no mobile signal.
- [ ] No gaps in the point stream longer than 30 seconds.
- [ ] Service survives device idle and survives switching to other apps.
- [ ] Force-killing the app mid-recording loses no more than the last few seconds.
- [ ] Rebooting the device mid-recording loses no points recorded before the reboot, and
      the app can start a location foreground service from a `BOOT_COMPLETED` receiver
      with `ACCESS_BACKGROUND_LOCATION` granted. **Verify this specifically** — it is the
      one part of the durability requirement that could turn out to be blocked by the
      platform on the developer's Android version.
- [ ] Exported GPX opens correctly in a third-party tool and the track looks right.
- [ ] Battery drain over a 2-hour recording measured and written down.

**If this fails:** do not proceed. Diagnose first. Fallbacks in order: tune location
request parameters; move work off the main thread; strip the service to its leanest form.
Report findings rather than working around them silently.

**Reference reading:** OpenTracks' recording service
(`codeberg.org/OpenTracksApp/OpenTracks`) is a mature working implementation of this exact
problem. Java, but the approach is worth studying.

---

## M1 — Android recorder MVP

Everything from M0 is deleted or rewritten properly. This is the first real code.

### M1.1 Project skeleton
- Repository root `trailog/`. Gradle multi-module: `:core`, `:app`. Version catalogs.
  Kotlin, Compose, Room.
- Application ID is `io.github.martinzitka.trailog`. Fixed; never changed afterwards.
- `applicationIdSuffix = ".debug"` on the debug build type, verified by installing debug
  and release side by side on one device.
- `:core` has zero Android dependencies — enforce with a test asserting the compile
  classpath.
- CI on GitHub Actions: build, unit tests, lint on every push.
- Renovate configured. `.gitignore` covers keystores, `local.properties`, `*.jks`.

### M1.2 Domain core (`:core`)
Pure Kotlin, developed test-first against fixture tracks.
- Domain models: `Activity`, `RawPoint`, `ActivityType`, `ActivityStats`.
- Client-generated UUIDv7 IDs so activities can be created entirely offline.
- Filtering pipeline: accuracy threshold, speed plausibility rejection, smoothing.
  A swappable strategy, not baked into callers.
- Statistics: distance (haversine over filtered points), elapsed time, moving time,
  elevation gain and loss, average and maximum speed, per-kilometre splits.
- **Every statistic is segment-aware.** Nothing is interpolated across a gap between
  segments — not distance, not elevation, not speed, not chart geometry.
- EGM2008 geoid correction for GPS altitude.
- Barometric elevation fusion: barometer for relative change, GPS/DEM for absolute anchor.
- GPX reading and writing.
- ~~FIT **reading**~~ — **dropped 2026-08-31.** It was wanted only to carry heart rate and
  cadence out of Sports Tracker, and the probe required by M2.1 showed there is none to
  carry. See ADR 0022. GPX is the migration format; no FIT parser is built, and `:core`
  gains no dependency for one. Revisit only if a future source actually holds sensor data.
- FIT **writing is optional** (2026-08-30). Nothing in M1 needs it; GPX covers portability,
  and CLAUDE.md's data-portability principle is satisfied without it. Do not let its absence
  block M1. Revisit if a target tool refuses GPX.

**Acceptance criteria**
- [ ] Fixture tracks in `core/src/test/resources/fixtures/`, including at least one real
      ride from M0 and one exported from an existing app with known-correct published
      statistics.
- [ ] Distance within 1% and elevation gain within 5% of reference values for every
      fixture; moving time within 2%; elapsed time exact. See `CLAUDE.md` for the full
      tolerance table.
- [ ] Test asserts `:core` has no Android or Ktor classes on its compile classpath.
- [ ] A fixture with a deliberate multi-segment gap proves no statistic interpolates
      across the boundary. Construct it by hand if no real one exists yet.
- [ ] Round-trip test: GPX in, GPX out, no data loss — including segment structure, which
      must survive as multiple `<trkseg>` elements. The same for FIT *if* FIT writing is
      built; FIT reading is covered by a parse test against a real file instead.

### M1.3 Recording engine

Follow "Recording durability and recovery" in `CLAUDE.md` — it specifies the service
configuration, the durability settings and the gap-based recovery policy.

- `RecordingEngine` interface in `:core`; Android foreground service implements it.
- Raw fixes persisted to Room on arrival, unfiltered, unmodified, with a segment index.
- Session state persisted in the database, never in memory or SharedPreferences.
- Session state machine: idle, recording, paused, stopping, recovering.
- `BOOT_COMPLETED` receiver that finds an interrupted session and applies the recovery
  policy.
- Recovery always opens a **new segment**; it never appends to the previous one.
- Heartbeat timestamp persisted periodically so gap length can be measured rather than
  guessed.
- Wake lock handling; notification showing live stats.

**Acceptance criteria**

Verified against a real device, using this kill matrix. For each: all points recorded
before the interruption survive, the session is recovered per policy, and a new segment
begins.

- [ ] `adb shell am force-stop <pkg>` — user-style kill
- [ ] `adb shell am crash <pkg>` — uncaught exception
- [ ] `adb shell kill -9 <pid>` — hard process kill
- [ ] `adb reboot` — device restart with an activity in progress
- [ ] Swiping the app from recents does **not** stop recording
- [ ] Battery drained to shutdown mid-activity, then recharged and booted (do this once,
      manually; it cannot be automated)
- [ ] All M0 acceptance criteria pass against the real implementation.
- [ ] Each of the three gap-length recovery branches exercised and behaving as specified.
- [ ] Instrumented test covers state machine transitions including recovery.

### M1.4 Persistence
- Room schema: `activities`, `raw_points` (carrying a segment index), `recording_sessions`,
  plus a derived `activity_stats` cache.
- `PRAGMA synchronous = FULL`. Durability beats throughput at 1 Hz.
- Schema exported and committed; migrations tested.
- A "recompute all derived data from raw points" path exists and is tested.

### M1.5 Screens

Build in this order. The last may slip without blocking the milestone.

Navigation is a bottom bar: Record, History, Sensors, Settings. Activity detail is pushed
from History.

**The "Definition of done — any screen" checklist in `CLAUDE.md` applies to all of them**
and is not repeated here. The criteria below are the screen-specific behaviour on top of
it.

#### 1. Record

States: permissions missing, permissions partial, ready, recording, paused, saving,
interrupted-session-found.

- [ ] From a cold start, recording begins in at most two taps; the activity type defaults
      to the last one used.
- [ ] Live display updates at least once per second: elapsed time, moving time, distance,
      current speed, elevation gain.
- [ ] Map shows the live trace and follows position; the user can pan away and re-centre.
- [ ] Stop requires confirmation. An accidental stop loses a ride.
- [ ] Recording continues correctly across rotation, backgrounding, and process death.
- [ ] The permissions-missing state names exactly what is missing and deep-links to the
      correct system settings page, and explains that "Allow all the time" is what makes
      recovery after a phone restart possible.
- [ ] An interrupted activity is surfaced clearly on next open, showing the gap length and
      what was done about it.
- [ ] A battery-optimisation warning appears when the app is not exempted, with a way to
      fix it.
- [ ] Screen-on behaviour while recording matches the documented decision.

#### 2. History

States: empty, populated, loading.

- [ ] The empty state explains how to record a first activity. A blank list is not
      acceptable.
- [ ] Each row shows activity type, date, distance, duration and elevation gain.
- [ ] Sorted most recent first.
- [ ] Scrolls smoothly with 500+ activities — use the imported Sports Tracker history as
      the test data set, not a handful of synthetic rows.
- [ ] Tapping a row opens Activity detail.
- [ ] Fully functional with no network.

#### 3. Activity detail

States: loading, loaded, map tiles unavailable offline.

- [ ] Opens with the map zoomed to the route's bounds.
- [ ] Elevation and speed charts render a 4-hour activity without visible jank.
- [ ] Summary shows distance, elapsed time, moving time, elevation gain and loss, average
      and maximum speed, and per-kilometre splits.
- [ ] The splits interval is selectable — 1, 2, 5 or 10 laps of the display unit, defaulting
      to one. Picking a longer interval re-laps the ride in `:core` rather than summing
      kilometre rows, exactly as the imperial preference does (ADR 0014).
- [ ] Gaps between segments are visible on the map and charts as breaks, never as straight
      lines joining the ends.
- [ ] Name, notes and activity type are editable and changes persist.
- [ ] Delete requires confirmation.
- [ ] GPX export available from this screen. FIT export optional — see M1.2.
- [ ] Degrades gracefully with no network and no cached tiles — the route still renders
      over a blank background rather than showing an error.

#### 4. Sensors and diagnostics

- [ ] Shows live GPS accuracy, satellite count, fix age and provider.
- [ ] Shows barometer reading, or an explicit "not available on this device".
- [ ] Shows recording service state, each location permission's state, and
      battery-optimisation status.
- [ ] Values update live while the screen is open.
- [ ] Adding a new sensor type later requires adding a row, not restructuring the screen.

#### 5. Settings

- [ ] Contains only settings that something actually reads. A setting with no effect is a
      bug, not a placeholder.

#### 6. Linked charts

Both live on the **fullscreen map**, opened by tapping the map preview on Activity detail:
the route with pan and zoom, and one chart beneath it carrying elevation on the left axis
and speed on the right, each with a visibility toggle. The selection is held as a distance
along the ride, not as a coordinate — a loop passes the same junction twice, so a
coordinate does not identify a moment.

- [ ] Touching or dragging the elevation or speed chart marks the corresponding position
      on the map.
- [ ] Selecting a position on the map marks the corresponding point on both charts.
- [ ] Remains responsive on a track of ~15,000 points.

### M1.6 Maps
- MapLibre Native Android.
- Map source abstraction: a source is a MapLibre style JSON plus an optional API key.
- Default source: self-hosted PMTiles built with Planetiler from a Czech Republic
  Geofabrik extract. Document the build in `infra/tiles/README.md`.
- Offline: PMTiles region packs downloadable to device storage.
- Additional online sources user-configurable with user-supplied keys. Mapy.com's terms
  prohibit caching or storing tiles, so it can only ever be an online layer — never
  include it in offline packs.

### M1.7 Export
- GPX export, single activity and all activities.
- Works entirely offline with no account.
- **FIT export is optional** (2026-08-30). GPX satisfies data portability; FIT writing is a
  nice-to-have that must not hold up M1. FIT *reading* is a separate matter and is still
  wanted for M2 — see M1.2.

### M1.8 Naming an activity when the recording is saved
The schema already carries `name` and `notes`, and Activity detail can already edit both.
What is missing is the moment that matters: naming the ride when you finish it, while you
still remember where you went.

- A prompt at the end of a recording, offering name and notes.
- **Skipping it must save the activity anyway.** Naming is metadata; a ride is never held
  hostage to it (CLAUDE.md: lose nothing).
- Both fields stay optional forever. History already derives a display title from type and
  date when the name is blank, and no formatted string is stored (ADR 0014).
- No migration: `activities.name` and `activities.notes` exist, and `updateMetadata` already
  writes them with an `updatedAt` bump for the sync tie-breaker.

**Filtering on it later** needs nothing now. Activity counts are in the hundreds, so a `LIKE`
scan over `name` and `notes` is adequate and an index would not help a leading-wildcard search
anyway. If full-text search is ever wanted, FTS5 is a later migration — do not build it now.

**M1 done when:** no activity has been lost across the recordings actually made.

> Originally "used as the only tracker for two weeks". Relaxed 2026-08-30 — the developer does
> not intend to ride exclusively with it, so a wall-clock exclusivity window would only ever
> block the milestone rather than inform it. The thing that criterion was really protecting —
> that nothing goes missing — is kept, and it is the part device testing cannot fake.

---

## M2 — Historical data migration

Independent of the backend. Do it early: it produces fixture data M1's tests want, and the
Sports Tracker export path could break at any time.

### M2.1 Export script (throwaway)
Lives in `tools/sports-tracker-export/`, clearly marked disposable.
- No official bulk export exists; the web UI offers per-workout GPX only, from the
  activity editing page.
- Community scripts use an undocumented endpoint of the form
  `api.sports-tracker.com/apiserver/v1/workout/exportGpx/<id>?token=<token>`, with the
  session token read from the `sessionkey` cookie.
- **An `exportFit` variant of the same endpoint is reported by several independent community
  sources**, switched by swapping that one path segment. It is not offered as a button in the
  web UI, which is why it is easy to conclude only GPX exists — the UI and the API differ here.
- **Both endpoints verified against the real account 2026-08-31.** GPX and FIT each returned
  HTTP 200 with a genuine body of the requested format. The community sources were right, and
  the 2016-era route still works.
- **FIT was dropped, and the question is closed.** It was only ever preferable for carrying heart
  rate and cadence natively. The probed workout has none: its GPX contains 5,276 track points
  and **zero** extension elements, and the FIT body is far too small to hold per-point sensor
  data — 111,475 bytes over 5,276 records is 21.1 bytes each, which is exactly a record of
  timestamp, latitude, longitude, altitude, distance and speed. Adding heart rate alone would
  need 116,072 bytes, more than the file contains.
- **Corrected 2026-08-31 against the full list of 1,557 workouts.** Nine *do* carry heart rate —
  eight runs from 2013–2016 and one MTB ride from 2020, 134–162 bpm. Cadence is zero across all
  1,557. FIT stays dropped: nine workouts is 0.6% of the history, their avg and max HR are already
  preserved in the workout-list JSON, and `RawPoint` has no heart-rate field to put per-point data
  in anyway. Do not repeat the original overstatement that the history carries none.
- **Consequence: GPX is the migration format.** No FIT decoder is written, `:core` gains no new
  dependency, and FIT leaves the plan — see M1.2 and ADR 0022.
- **Manually entered workouts have no track.** Their distance and duration exist only in the
  workout-list JSON, which is why `list` saves that response verbatim. An empty GPX is a real
  case the importer must handle, not a failure.
- Expect breakage without notice. Not a maintained feature.

**Export completed 2026-08-31: 1,555 of 1,557 files, 1,549 of them intact.** Verified by the
tool's own `verify` subcommand, which audits the archive offline against the saved workout list.

**Format validity is not proof of content, and assuming it was hid real damage.** `fetch` accepted
every body that sniffed as GPX — correctly, they *were* GPX — including four exports carrying a
single track point for rides of 41 to 49 km. Nothing in the download log looked wrong. The audit
exists because of that, and its rule is a loose sanity bound (one point per 500 m, against a real
density of one per 10–20 m) chosen to catch collapse rather than grade quality.

**The discriminator for an empty export is `isManuallyAdded`, never distance.** A hand-entered swim
reports a real distance and has no track; so does a ride whose track was lost. The first is
expected, the second is data loss, and distance cannot tell them apart. 28 of the 30 track-less
workouts are manual swims; the other two are a recorded walk and a recorded ride.

**Eight workouts are damaged upstream** — 0.5% of the history, all with title, date, distance,
duration and elevation intact in the workout list:

| Kind | n | Detail |
|---|---|---|
| Server refuses to export | 2 | `error 535, "Couldn't export workout to GPX format"`. FIT returns a valid but **empty** 306-byte file — 0.47 bytes per polyline point — so the per-point data is genuinely gone, not merely unreachable through GPX. |
| Exported empty, genuinely recorded | 2 | One of them is titled "Auto-recovered" — Sports Tracker's own crash recovery ran, and the track evidently did not survive it. |
| Exported with 1–2 points | 4 | Three fall on consecutive days, which points at something wrong at recording time rather than random server-side loss. |

**Do not reconstruct these from the stored polyline.** It is tempting — the polylines reproduce
99.6–99.8% of the reported distance at 16–20 m spacing — but they carry **no timestamps and no
elevation**, and `RawPoint.time` is non-null by design. Spreading points evenly across the duration
would fabricate data and silently corrupt moving time and every speed figure. These import as
track-less activities, exactly like the manual swims: honestly summary-only beats plausibly wrong.

### M2.2 Import CLI (`:tools:importer`)

> **Do M2.3's foundation slice first** (2026-08-31). Nine exported workouts carry per-point heart
> rate, and the importer must write complete rows on its first pass — the dedupe rule means a later
> re-run skips existing activities instead of backfilling them. See M2.3 below.

Durable. A JVM CLI that uses `:core` for parsing, so there is exactly one GPX parser in the
project.
- Reads a folder of GPX files, produces activities, deduplicates on re-run. FIT and TCX are
  not read — FIT was dropped above, and no TCX source exists to test against.
- **Handles a track with no points**, which is what a manually entered workout exports as.

**Activity types come from a per-account mapping file, never from a table in this repo.**
Sports Tracker identifies types by a numeric `activityId` that appears **only in the workout-list
JSON** — the GPX export carries no `<type>` element at all, which is why `list` is mandatory rather
than convenient.

That mapping cannot be hardcoded, and not only for privacy. Sports Tracker offers generic "Other
1…6" slots, so while the common ids are stable across accounts (walking, running, cycling, mountain
biking), the rest are whatever a given user happened to assign them — the same id means different
activities to different people. A table baked into the importer would be wrong for everyone except
its author.

So the importer reads two tab-separated files, supplied by the user and kept out of version
control alongside the export they describe:

| File | Columns | Purpose |
|---|---|---|
| `activity-types.tsv` | `activityId`, `trailogType` | The account's id-to-type mapping. |
| `type-overrides.tsv` | `workoutKey`, `trailogType`, `note` | Per-workout corrections. |

**The overrides file is not optional bookkeeping.** An id can hold more than one real activity —
a generic "Other" slot used for two unrelated sports across different years — so no id-to-type rule
can be correct on its own. Deriving the mapping is a human judgement over the workout list, and
putting that judgement in a reviewable data file beats burying guesses in code. Unmapped ids fall
back to `OTHER` rather than failing the import; the run reports which ids landed there.

`ActivityType` therefore gains ten values: `CROSS_COUNTRY_SKIING`, `DOWNHILL_SKIING`, `SWIMMING`,
`CANOEING`, `INLINE_SKATING`, `ICE_SKATING`, `SNOWKITING`, `BOBSLEIGH`, `RAFTING`, `OTHER`. Adding
enum values needs no migration — the type is stored by name — but each needs a
`movingSpeedThreshold` and a `maxPlausibleSpeed`, and fifteen entries in the Record screen's type
picker is a UI question of its own.

**Name and description are inverted relative to GPX convention.** Sports Tracker writes the user's
title into `<metadata><desc>` and an auto-generated date into `<name>` (and into `<trk><name>`).
The JSON agrees: `description` holds the title, `workoutName` holds the date. So the importer maps
`<desc>` to the activity name and **discards** the date-shaped `<name>` rather than storing a
formatted timestamp (ADR 0014); History already derives a title from type and date when the name
is blank. The inversion is gated on `creator="Sports Tracker"` so it cannot corrupt a file from
anywhere else, and `:core` parses both fields faithfully without applying it.

**Sports Tracker's own statistics are sometimes wrong.** One workout in the archive reports
0.44 km over a track whose bounding box is 3.5 km across. Trailog recomputes from raw points, so
importing it fixes the figure — but the acceptance comparison below must treat outliers as
*suspected source errors* rather than as import bugs, and report them for inspection instead of
failing.
- Before M3 exists: writes directly to a local store or emits app-importable output.
- After M3 exists: authenticates and uploads via the sync API as an ordinary client.
- Also the natural home for driving the M2.1 scraper.

**Acceptance criteria**
- [ ] Full Sports Tracker history exported to local files and backed up before anything
      else touches it.
- [ ] Imported activities' statistics match the source app's published figures within the
      tolerances in `CLAUDE.md`.
- [ ] Re-running the import creates no duplicates.
- [ ] A representative sample added to the `:core` fixture set.

---

## M2.3 Sensor sample stream

**Decided 2026-08-31, and scheduled *before* M2.2 deliberately.** Nine of the exported Sports
Tracker workouts carry per-point heart rate, and the importer should write complete rows on its
first pass. The alternative — import now, backfill later — is worse than it looks: the importer
deduplicates, so a re-run *skips* existing activities rather than filling in what was missing, and
by then hundreds of imported activities will have been renamed and retyped by hand. A backfill path
would exist for one purpose and then rot.

### Shape

**A separate timestamped sample stream, not more columns on `RawPoint`.** A field on `RawPoint`
would only capture a reading when a GPS fix arrives — nothing during a signal blackout or a pause —
and a heart-rate strap emits at ~1 Hz regardless of what the GPS is doing. Sensor data is its own
stream that happens to share a clock with the track.

- `:core` gains `SensorSample(time, type, value)` and a `SensorType` enum. Platform-free, as ever.
- `:app` gains a `sensor_samples` table (activity id, time, type, value), Room migration 2 → 3,
  indexed on `(activityId, time)`. Non-destructive, like every migration here.
- Samples align to segments **by timestamp**, and are never interpolated across a segment boundary
  — the same rule the statistics already follow.
- Generic `type` + `value` rather than a column per sensor, so a new sensor is a new enum constant
  rather than a migration. This matches the Sensors screen criterion already in M1.5: "adding a new
  sensor type later requires adding a row, not restructuring the screen".

### Units: bpm, and why that is not a violation

`CLAUDE.md` requires SI internally with no exceptions. **Each `SensorType` declares its own unit
instead** — SI where a meaningful SI unit exists (watts, pascals), the domain-standard unit where SI
would be perverse (bpm, rpm).

The reason is not taste. BLE reports heart rate as an integer bpm, and 140 bpm in hertz is 2.333…,
which does not round-trip: you get 139.99999 back. Storing hertz would *introduce* the precision
loss the SI rule exists to prevent. The rule's purpose — exactly one unit per field, converted only
at the display edge in `Formatter` — is fully preserved, because there is exactly one unit per
sensor type and nothing converts anywhere else. Record it as an ADR when the code lands.

### Phasing

1. **Foundation** — `:core` model, Room table and migration, repository read/write, GPX
   `gpxtpx:hr` reading, and Trailog's own GPX writing so a recorded activity still round-trips.
   This is all M2.2 needs. **Do this first.**
2. **Producers** — BLE heart-rate straps (GATT service `0x180D`, characteristic `0x2A37`, one of
   the most rigidly standardised profiles in Bluetooth), wired into the existing foreground
   service. Promoted from the backlog. Needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
3. **Display** — a live row on the Sensors screen, and heart rate as a chart profile on Activity
   detail alongside elevation and speed.

### Not doing: step counting

Android would make it easy — `Sensor.TYPE_STEP_COUNTER` is hardware-fused and nearly free to read,
needing only `ACTIVITY_RECOGNITION`; no accelerometer maths is involved. It is skipped because the
data is not worth having. Of 1,263 exported workouts carrying a step count, roughly 780 are cycling,
mountain biking or downhill skiing — a phone pedometer counting vibration. Only running, walking and
hiking produce a meaningful figure, and step counting is what a phone's health app already does.
Trailog's value is accurate GPS-derived statistics.

**Imported summary figures are a different category and are not stored.** A workout's total step
count, or an avg/max heart rate from the source app, is a third-party derived figure Trailog can
never recompute or verify — it cuts directly against "every statistic is a recomputable view over
raw data". Per-point samples have no such problem; totals do. Imported activities keep what they
can prove.

---

## M3 — Backend MVP

Do not start until M1 is done. Design the sync protocol from what the phone actually needs.

### M3.1 Foundation
- Ktor + PostgreSQL **with PostGIS** + Flyway. Docker Compose for deployment.
- `:server` depends on `:core` — statistics and parsing are never reimplemented.
- Multi-tenant schema from the first migration: every row carries a user key, every query
  is scoped. Ship with registration disabled or invite-only.
- **Store geometry properly from day one**, even though the features that use it come in
  M5: a `geography(LineString)` column for the route, a bounding box, and a simplified
  polyline for map previews, all spatially indexed. Retrofitting this later is expensive.
- **Privacy zones in the data model from day one**: named centre points with a radius, per
  user. Track data within a zone is suppressed on any outbound share or export where the
  user asks for it. The UI can come later; the schema should not have to change.

### M3.2 Auth
Follow the security standards section of `CLAUDE.md`.

**Acceptance criteria**
- [ ] Tests that specifically attempt cross-tenant access and assert failure, for every
      endpoint.
- [ ] Test asserting refresh token reuse invalidates the session family.
- [ ] Test that records log output during a sync and asserts no coordinate appears in it.

### M3.3 Sync
Activities are effectively immutable once recorded. Exploit this.
- Client-generated UUIDv7 IDs; the server never mints activity IDs.
- **Raw points sync to the server**, not just the smoothed track or the summary. The
  server's copy must be a complete backup from which everything can be recomputed.
- Mutable metadata is only: name, notes, activity type, visibility. Last-write-wins on
  `updated_at` is sufficient. **Do not build CRDTs.**
- Deletes are tombstones so they propagate.
- Uploads idempotent and resumable; interrupting mid-sync is safe.

### M3.4 Import, export, backfill
- Server-side import of GPX, TCX and FIT via `:core`. Now straightforward — the server
  reads plaintext, so the web app can upload a file and let the server parse it rather
  than reimplementing FIT parsing in TypeScript.
- Export in the same formats, so activities move to Endurain or anything else.
- A backfill job that recomputes all derived statistics from raw points across all users.
  This is why raw data is preserved; make sure it actually works.

### M3.5 Deployment and data rights
- Docker Compose: Postgres/PostGIS, server, reverse proxy.
- Deployment documentation covering the recommendations in `CLAUDE.md`.
- Encrypted backup procedure (Restic or Borg), documented and verified by an actual
  restore.
- "Export all my data" and "delete my account and all its data" endpoints.

---

## M4 — Web frontend

SvelteKit + MapLibre GL JS.

- Activity list, activity detail with map and charts, aggregate statistics.
- File upload for import; the server does the parsing.
- Access token in memory only; refresh token in httpOnly SameSite=Strict cookie.
- No third-party CDNs, no analytics, no external fonts. Everything self-hosted.
- Served as static files by the backend container to keep deployment to one service.

---

## M5 — Spatial features

The reason PostGIS is in the stack. Scheduled, not backlog.

### M5.1 Heatmap
- Aggregate all of a user's tracks into a density layer.
- Served as vector or raster tiles generated server-side and cached.
- Respects privacy zones.

### M5.2 Route matching
"How many times have I ridden this loop, and what was my fastest?"

Recommended approach — cheap, noise-tolerant, and indexable:
- Snap each track's points to a grid (start with ~50 m cells).
- The resulting **set of cells** is the route fingerprint.
- Compare routes by Jaccard similarity between cell sets; cluster above a threshold.
- Robust to GPS noise and slightly different start points, and needs no expensive
  curve-distance maths.

Fréchet or Hausdorff distance over simplified polylines is the textbook alternative and is
more precise, but much heavier. Start with the grid approach and only escalate if it
proves inadequate.

**Decide explicitly:** is the same loop ridden in reverse the same route? Pick an answer
rather than discovering one.

### M5.3 Activity sharing
Two people ride together; one forgot to record. Not a social feature.
- Sharing produces a **copy** in the recipient's tenant, which they then own outright.
  The sender deleting theirs does not affect the recipient.
- Implemented as export plus import behind a transfer link — reuses existing machinery.
- **Apply the sender's privacy zones to the shared copy**, or offer start/end truncation
  at share time. A share button that leaks the sender's home coordinates would defeat the
  point of the project.

---

## Backlog (not scheduled)

- BLE sensors: heart rate straps, cadence, power. The M1.5 sensors screen is the hook.
- Auto-pause with per-activity-type thresholds.
- Route planning and GPX route import with turn prompts.
- Photos attached to activities.
- Hillshading and contour lines in the self-hosted tile style; rendering Czech KČT trail
  waymarks from OSM `osmc:symbol` relations.
- **A self-hosted tile service, shared by more than one client.** On-device PMTiles cover
  the region the user built; outside it there is nothing. The intended answer is the
  user's own tile server — the same host the backend runs on — serving the archives the
  phone falls back to when it leaves its downloaded region, with the style catalogue
  alongside them so a client can offer a choice of styles.

  The reason it is worth designing as a service rather than as a Trailog endpoint: a
  standalone map viewer app (browse and switch styles, no tracking) would want exactly the
  same thing, and one server can feed both. Trailog's map layer already goes through
  `MapSource` (ADR 0015), so a remote source is a new variant rather than a rewrite. No
  third-party egress either way — every host involved is the user's own.
- TOTP MFA.
- iOS, once Apple hardware exists.
