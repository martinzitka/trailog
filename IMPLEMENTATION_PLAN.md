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
- GPX and FIT reading and writing.

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
      must survive as multiple `<trkseg>` elements. Same for FIT.

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
- [ ] Gaps between segments are visible on the map and charts as breaks, never as straight
      lines joining the ends.
- [ ] Name, notes and activity type are editable and changes persist.
- [ ] Delete requires confirmation.
- [ ] GPX and FIT export available from this screen.
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
- GPX and FIT export, single activity and all activities.
- Works entirely offline with no account.

**M1 done when:** the developer has used the app as their only tracker for two weeks and
hasn't lost an activity.

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
  session token read from the `sessionkey` cookie. A FIT variant exists.
- **Prefer FIT** — it carries heart rate and cadence natively, where GPX needs extensions.
- Expect breakage without notice. Not a maintained feature.

### M2.2 Import CLI (`:tools:importer`)
Durable. A JVM CLI that uses `:core` for parsing, so there is exactly one GPX parser and
one FIT parser in the project.
- Reads a folder of GPX/FIT/TCX files, produces activities, deduplicates on re-run.
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
- TOTP MFA.
- iOS, once Apple hardware exists.
