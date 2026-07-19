# Trailog

A privacy-first GPS activity tracker for cycling, MTB, running, hiking and walking.
Self-hosted, no third-party analytics, no telemetry, no paywalls. See
[`CLAUDE.md`](CLAUDE.md) for the principles and [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)
for the roadmap.

**Status: M0 — recording spike.** The Android app in `:app` is a deliberately throwaway
spike (package `io.github.martinzitka.trailog.spike`) whose only job is to prove background
GPS recording survives on real hardware. It is deleted/rewritten for M1. The `:core` module
is the first real, permanent code.

## Modules

| Module | What | Depends on |
|---|---|---|
| `:core` | Pure Kotlin/JVM. Domain models, geodesy, IDs, statistics, parsers. Zero Android/Ktor. | — |
| `:app`  | Android app. Currently the M0 recording spike. | `:core` |

`:server` and `:tools:importer` arrive at M3 and M2. See
[`docs/adr/`](docs/adr) for architecture decisions.

## Prerequisites

- JDK 17
- Android SDK: platform 35, build-tools 35 (for `:app`)
- A `local.properties` at the repo root with `sdk.dir=<your Android SDK path>` (gitignored)

## Build & test

```bash
./gradlew :core:test          # run the pure-Kotlin unit tests (the high-coverage bar)
./gradlew :app:assembleDebug  # build the M0 debug APK
./gradlew check               # everything: build + tests + Android lint
```

The debug build carries `applicationIdSuffix = ".debug"` so it coexists with a future
release build on one device.

## M0 field testing (developer, on real hardware)

The spike cannot be validated in an emulator or by automated tests — it must run outdoors
on the actual device. Install the debug APK, then in the app:

1. Grant **foreground location + notifications** (button 1).
2. Grant **background location** — "Allow all the time" (button 2, or app settings).
   This is what makes reboot recovery possible; without it a location foreground service
   cannot start from `BOOT_COMPLETED` on Android 14+.
3. Grant the **battery-optimisation exemption** (button 3).
4. **Start recording**, pocket the phone, screen off.

Acceptance criteria to check off (from `IMPLEMENTATION_PLAN.md` M0):

- [ ] Two outdoor activities ≥ 2 h each; at least one in poor/no mobile signal.
- [ ] No gap in the point stream longer than 30 s.
- [ ] Survives switching apps and device idle.
- [ ] `adb shell am force-stop <pkg>` mid-recording loses no more than a few seconds.
- [ ] `adb reboot` mid-recording: prior points survive; service restarts from boot.
- [ ] Swiping from recents does **not** stop recording.
- [ ] Battery drained to shutdown, recharged, booted — points before shutdown survive.
- [ ] Exported GPX (button "Export GPX to Downloads") opens correctly in a third-party
      tool and the track looks right.
- [ ] Battery drain over a 2-hour recording measured and written down.

The debug package id is `io.github.martinzitka.trailog.debug`. Record findings; **if M0
fails, do not proceed to M1** — diagnose first (see the plan's fallback order).

## License

TBD.
