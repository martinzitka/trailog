# CLAUDE.md

Guidance for Claude Code working in this repository. Read `IMPLEMENTATION_PLAN.md` for
what to build and in what order. This file is about *how* to build it.

## What this is

**Trailog** — a privacy-first GPS activity tracker for cycling, MTB, running, hiking and
walking.
Self-hosted, no third-party analytics, no telemetry, no paywalled features. It exists
because the commercial alternatives are freemium and monetise the user's location history.

**Scope decision: Android only for v1.** The developer owns no Apple hardware. iOS is
deferred, not abandoned — see "Keeping iOS possible". Do not add iOS scaffolding.

## What "privacy" means here

Precisely one thing: **the user's activity data never reaches a third party.**

It does not mean the data is hidden from the server operator, who is the user. Plaintext
tracks in the user's own database are fine and expected — the server needs to read
geometry to do spatial queries.

The practical consequences are:

- No analytics, no telemetry, no crash reporters that transmit data, no third-party CDNs,
  no externally hosted fonts, no Google Maps SDK.
- Adding any network call to a domain the user does not control requires explicit
  discussion first. This is the single most important rule in this file.
- Backups that leave the machine must be encrypted client-side. An unencrypted backup in
  someone else's cloud storage is third-party sharing.
- Coordinates stay out of logs, traces and error messages. Not because logs are assumed
  hostile, but because logs get pasted into bug reports and issue trackers.

## Non-negotiable principles

If a task seems to require breaking one of these, stop and ask.

1. **Raw points are immutable and sacred.** Location fixes are persisted exactly as the OS
   delivered them, and never modified or deleted. Filtering, smoothing, and every derived
   statistic is a *recomputable view* over raw data. Algorithms can then improve
   retroactively and bugs can be diagnosed after the fact. Never filter on ingest, and
   never sync only the smoothed version — the server stores raw points too, or the backup
   is a lossy derivative.
2. **Local-first.** The phone's database is the source of truth. The app is fully
   functional with no network and no account, forever. The backend is a sync, backup,
   analysis and web-viewing service — never a dependency for recording.
3. **Lose nothing.** A crash, an OOM kill, or a dead battery must never cost more than the
   last few seconds of an activity. Points are written to disk as they arrive, never
   buffered in memory for the duration of a session.
4. **No third-party egress.** See above.
5. **Data portability.** Full GPX and FIT export must always work, offline, without an
   account.

## Settled decisions

Recorded here because they constrain design and should not be silently revisited. Write
new decisions as ADRs in `docs/adr/`.

**No end-to-end encryption.** Considered and rejected. E2EE would preclude server-side
spatial queries, and heatmaps and route matching are wanted features. The architecture
that E2EE would have required — local-first, stats computed on-device, raw data preserved
— is retained anyway, because it was correct for independent reasons. The server reads
plaintext geometry and this is intended.

**Multi-tenant, not social.** Several users may share an instance; each sees only their
own data. There is no follower graph, no feed, no shared visibility. Activity sharing is
implemented as a *copy* into the recipient's tenant, not as a permission grant.

**No commercial or paid dependencies.** Hard constraint, not a preference.

## Stack

| Area | Choice |
|---|---|
| Language | Kotlin, everywhere except the web frontend |
| Mobile UI | Jetpack Compose, Material 3 |
| Mobile DB | Room (SQLite) |
| Maps | MapLibre Native Android |
| Tiles | Self-hosted PMTiles built with Planetiler, from OpenStreetMap |
| Backend | Ktor |
| Database | PostgreSQL **with PostGIS** |
| Migrations | Flyway, forward-only, checked in |
| Web (M4) | SvelteKit + MapLibre GL JS |
| Build | Gradle with version catalogs |
| Tests | JUnit 5 + kotlin.test; Turbine for flows; Testcontainers for the server |

PostGIS is in from the first migration. Heatmaps and route matching are planned, and
retrofitting spatial indexing onto an established schema is the expensive path.

### Identity

- Display name: **Trailog**. Changeable at any time.
- Application ID: **`io.github.martinzitka.trailog`**. Settled. Do not change it, do not
  propose changing it. Android namespaces app storage by application ID, so changing it
  orphans the on-device database — and it is immutable outright once published to Play or
  F-Droid.
- Debug builds carry `applicationIdSuffix = ".debug"` so a debug build and the real
  recording app coexist on one device. This matters here: the developer's actual rides
  live in the release build while agents throw experimental builds at the same phone.

Package roots:

| Module | Package |
|---|---|
| `:app` | `io.github.martinzitka.trailog` |
| `:core` | `io.github.martinzitka.trailog.core` |
| `:server` | `io.github.martinzitka.trailog.server` |
| `:tools:importer` | `io.github.martinzitka.trailog.tools.importer` |

The import tool is `importer`, never `import` — the latter is a reserved keyword in Kotlin
and Java and is illegal as a package segment.

### Module layout

```
:core           Pure Kotlin/JVM. Zero Android, zero Ktor dependencies.
                Domain models, GPS filtering, statistics, GPX/FIT/TCX parsing and
                writing, sync protocol DTOs. THE important module.
:app            Android app. Compose UI, Room, foreground service, MapLibre.
:server         Ktor backend. Depends on :core.
:tools:importer JVM CLI for bulk import. Depends on :core. An ordinary API client.
```

**`:core` must stay platform-free.** No `android.*`, no Ktor types, no file I/O in domain
logic. It is imported by the phone, the server and the CLI, so a statistic can never be
computed two different ways and a FIT file can never be parsed by two different parsers.

Do **not** set up Kotlin Multiplatform now. It buys nothing until iOS exists and costs
Gradle complexity today.

## Domain gotchas

These are the things that make a homemade tracker produce numbers that look wrong.

**GPS noise.** Raw fixes wander, especially under tree cover and when stationary.
Computing distance over unfiltered points inflates it substantially. Filter on reported
accuracy, reject implausible speed jumps, smooth before integrating. Distance uses the
haversine formula over filtered points.

**Altitude has two separate problems.**
- GPS altitude is relative to the WGS84 ellipsoid, not mean sea level. In Czechia the
  geoid separation is roughly 44–45 m. Apply an EGM2008 correction or every altitude
  displayed is wrong by that margin.
- Barometric pressure gives excellent *relative* altitude change but absolute altitude
  drifts with weather. Use the barometer for gain and loss, and GPS or DEM data to anchor
  absolute altitude. Detect barometer availability at runtime — many phones lack one.

**Elevation gain is the most-compared and most-wrong statistic.** Naively summing positive
altitude deltas produces wildly inflated numbers. Smooth aggressively and apply a
threshold before accumulating.

**Moving time and elapsed time are different numbers.** Store both, always, even before
auto-pause has a UI. Pause thresholds differ per activity type — walking is not cycling.

**Timestamps come from the location fix, not the device clock.** Clocks jump.

**Assume fixes arrive out of order and duplicated.** Sort and dedupe on read.

**Tracks have gaps, and gaps are explicit.** An activity is a sequence of *segments*, not
one continuous line. A crash, a process kill, a reboot, or a signal blackout ends one
segment and begins another. This maps directly onto GPX, which represents it as multiple
`<trkseg>` elements inside a single `<trk>`.

**Never interpolate across a segment boundary.** No distance, no elevation gain, no speed,
no chart line. A naive implementation draws a straight line from where recording stopped
to where it resumed and silently adds kilometres to the ride. Every statistics function
must be segment-aware, and there must be a test for it.

**Data volume is not a problem.** 1 Hz for four hours is ~14,400 rows. Do not compress, do
not optimise, do not invent a binary format.

## Android specifics

**Recording runs in a foreground service** with `foregroundServiceType="location"` and a
persistent notification. This is the only sanctioned mechanism; there is no alternative.

**Permissions are a multi-step flow, not a checkbox.** Foreground location must be granted
before background location can even be requested, and "Allow all the time" requires
sending the user to system settings on Android 10+. `POST_NOTIFICATIONS` is required on
13+. Foreground service types must be declared on 14+. Budget real time for this.

**OEM battery management will kill the service** on Samsung, Xiaomi and Huawei devices
regardless of correctness. Request a battery-optimisation exemption and surface the issue
to the user; link to dontkillmyapp.com. No library solves this.

**Never validate location changes on an emulator alone.** The emulator cannot reproduce
real GPS hardware, background suspension under memory pressure, or thermal behaviour. When
a change touches recording, say explicitly that it needs a real outdoor test rather than
claiming it works.

## Recording durability and recovery

An in-progress recording must survive: the app being backgrounded, the app being swiped
from recents, the process being killed by the system, a crash, and a device reboot.

**Session state lives in the database, never in memory or SharedPreferences.** A recording
session is a row with a state and a start time. Any process can read it and work out what
was happening.

**Write durability.** Room defaults to WAL journalling with `synchronous=NORMAL`, which can
lose recently committed transactions on power loss. Set `PRAGMA synchronous = FULL`. At
1 Hz the write volume is negligible and durability is the whole point.

**Service configuration.**
- `android:stopWithTask="false"` so swiping the app from recents does not stop recording.
  Some OEMs ignore this; declare it anyway.
- Return `START_STICKY` so the system restarts the service after killing it for memory.
- Persist a heartbeat timestamp periodically, so a later recovery can measure how long the
  service was actually dead rather than guessing.

**Reboot recovery is possible but permission-gated.** `location` is *not* among the
foreground service types that `BOOT_COMPLETED` receivers are forbidden to launch
(microphone is blocked from API 34; camera, phone call, data sync, media playback and
media projection from API 35). However, on Android 14+ the system verifies at service
creation that the app currently holds the permission for the service type, and
`ACCESS_FINE_LOCATION` is while-in-use only. An app in the background at boot therefore
fails with a `SecurityException` unless it holds **`ACCESS_BACKGROUND_LOCATION`**.

That makes "Allow all the time" a functional requirement for reboot recovery, not a
nice-to-have. The permission-request flow must explain this honestly.

Required manifest permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `RECEIVE_BOOT_COMPLETED`,
`POST_NOTIFICATIONS`.

**Recovery policy — branch on gap length.** Raw data is preserved and a wrongly-resumed
tail can be trimmed, whereas a wrongly-abandoned ride is lost forever. The asymmetry
favours resuming.

| Gap since last fix | Behaviour |
|---|---|
| under ~15 min | Resume automatically into a new segment; notify the user it happened |
| ~15 min to ~3 h | Notify with Resume / Finish actions; do nothing until answered |
| over ~3 h | Finalise the activity automatically; notify |

Thresholds are constants in one place, not scattered magic numbers. Every recovery starts
a **new segment** — never append to the previous one.

## Security standards

These are implementation requirements. Follow current best practice; do not weaken a
standard for convenience, and do not add debug backdoors, default credentials, or
"temporary" bypasses.

**Credentials.**
- Argon2id for password hashing, with current OWASP parameters. Never any other scheme.
- No plaintext passwords anywhere: not in the database, not in logs, not in memory longer
  than necessary, not in test fixtures.
- No secrets in the repository under any circumstances. Configuration via environment
  variables or Docker secrets. `.gitignore` covers keystores, `local.properties`, `*.jks`.
- The Android signing keystore lives outside the repo and is backed up separately.

**Tokens and sessions.**
- Short-lived access tokens, rotating refresh tokens with reuse detection.
- Android: tokens in EncryptedSharedPreferences backed by the Android Keystore. Never
  plain SharedPreferences, never a file.
- Web: refresh token in an httpOnly, Secure, SameSite=Strict cookie; access token in
  memory only. Never localStorage or sessionStorage.
- Rate limiting and progressive backoff on all auth endpoints.
- Schema accommodates TOTP MFA without a painful future migration.

Endurain's developer documentation describes a well-considered OAuth 2.1 hybrid token
model and is worth reading before designing this.

**API.**
- Every query is scoped by the authenticated user; every row carries a tenant key.
  Cross-tenant access is a data breach, not a bug — write tests that specifically attempt
  it and assert failure.
- Parameterised queries only. Validate and constrain all input at the boundary.
- Upload endpoints are idempotent; sync must be safe to retry.
- TLS only. Strict CORS allowlist, CSP, HSTS.

**Supply chain.**
- Renovate or Dependabot enabled. Gradle dependency verification metadata committed.
- Prefer boring, well-established libraries with active maintenance.

**Backups.** Any backup leaving the machine is encrypted client-side — Restic or Borg.
This is a privacy requirement, not just a security one.

## Deployment guidance (recommended, not required)

Operator's choice, documented for users rather than enforced in code:

- Full-disk encryption on the host.
- VPN or Tailscale access rather than public internet exposure. If exposed publicly,
  document the risks.
- Tested backup *and restore* procedure. A backup that has never been restored is a guess.

## Legal note

The developer is in the EU. Location data is personal data under GDPR. "Export all my
data" and "delete my account and everything in it" are product features, not afterthoughts
— and they are useful to the developer regardless of any legal obligation.

## Keeping iOS possible

Do not write iOS code. Do preserve the option:
- `:core` stays JVM-pure so it can become a KMP module.
- No Android types leak into domain models or the sync protocol.
- The recording engine sits behind an interface; the Android foreground service is one
  implementation.

## Units and formatting

**SI everywhere internally. No exceptions.** Metres, seconds, metres per second, degrees,
Pascals. Every model field, every database column, every API payload, every intermediate
calculation.

Conversion and formatting happen **only at the display edge**, in a single formatting
utility on the client. A user preference for imperial units will be added later; it must
change nothing but that utility.

- Field names carry no unit suffix, because there is only one unit. `distance`, not
  `distanceMeters`.
- No formatted string is ever stored or transmitted. Format at render time.
- No formatting logic in a Composable or a ViewModel — call the utility.

This sounds trivial. It is exactly the discipline that prevents a screen displaying
elevation in feet eighteen months from now.

## Accuracy tolerances

When validating statistics against a reference track with known-correct published figures:

| Statistic | Tolerance |
|---|---|
| Distance | 1% |
| Elevation gain / loss | 5% |
| Elapsed time | exact |
| Moving time | 2% |

Elevation gets a looser bound deliberately — it is genuinely harder, and reference
implementations disagree with each other. Do not chase noise below these thresholds.

## Testing policy

Coverage expectations differ by module. Do not apply one number across all of them; that
produces meaningless tests on UI code.

- **`:core`** — the high bar. Pure functions, no excuses. Every public function tested;
  every statistics function tested against real fixture tracks in
  `core/src/test/resources/fixtures/`. Aim for ~90% line coverage and mean it.
- **`:server`** — meaningful coverage of handlers and sync logic, plus the mandatory
  security tests listed in the plan (cross-tenant access, token reuse, log scrubbing).
  Those are required regardless of any coverage figure.
- **`:app`** — ViewModels, mappers and state logic are tested. Composables are not chased
  for coverage; a small number of Compose UI tests cover navigation and critical
  interactions. Recording behaviour cannot be verified by tests at all — see Android
  specifics.

When changing a statistics algorithm, run it over all fixtures and report the before/after
deltas. Silent changes to historical numbers are a bug.

## Definition of done — any screen

Applies to every screen; the plan does not repeat it.

- [ ] Empty, loading, error and populated states all implemented. No blank screen is ever
      a valid state.
- [ ] Renders correctly in light and dark theme.
- [ ] No hardcoded colours — theme tokens only. No hardcoded user-facing strings — string
      resources only, even while English-only.
- [ ] Survives configuration change (rotation, theme switch) without losing state.
- [ ] Touch targets at least 48dp; content descriptions on icon-only controls.
- [ ] ViewModel logic unit tested; navigation into and out of the screen covered by a
      Compose UI test.

## Conventions

- Conventional Commits.
- Every non-obvious architectural choice gets a short ADR in `docs/adr/`.
- Don't add a dependency to `:core` without asking.
- Leave no TODO that conceals unfinished work. If something is incomplete, say so in the
  response, not in a comment.

## Commands

<!-- Fill in as scaffolding lands -->

```
./gradlew :app:assembleDebug
./gradlew :core:test
./gradlew :server:run
./gradlew check
docker compose -f infra/docker-compose.yml up
```
