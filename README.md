# Trailog

A privacy-first GPS activity tracker for cycling, MTB, running, hiking and walking.
Self-hosted, no third-party analytics, no telemetry, no paywalls. See
[`CLAUDE.md`](CLAUDE.md) for the principles and [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)
for the roadmap.

**Status: M1 — Android app.** The `:core` domain (M1.2), the durable recording engine and
on-device recovery (M1.3), and Room persistence with a recompute path (M1.4) are done and
device-proven. **M1.5 (screens) is in progress:** the Compose navigation scaffold and the
**Record** screen are built; History, Activity detail, Sensors and Settings follow. The M0
recording spike has been removed — the app is now real code with application id
`io.github.martinzitka.trailog` (debug: `…​.debug`).

## Modules

| Module | What | Depends on |
|---|---|---|
| `:core` | Pure Kotlin/JVM. Domain models, geodesy, IDs, statistics, GPX parsing. Zero Android/Ktor. | — |
| `:app`  | Android app: Compose UI, Room, the recording foreground service. | `:core` |

`:server` and `:tools:importer` arrive at M3 and M2. See
[`docs/adr/`](docs/adr) for architecture decisions.

## Prerequisites

- JDK 17
- Android SDK: platform 35, build-tools 35 (for `:app`)
- A `local.properties` at the repo root with `sdk.dir=<your Android SDK path>` (gitignored)

## Build & test

```bash
./gradlew :core:test          # pure-Kotlin unit tests (the high-coverage bar)
./gradlew :app:testDebugUnitTest  # app JVM tests (ViewModels, Room, Compose via Robolectric)
./gradlew :app:assembleDebug  # build the debug APK
./gradlew check               # everything: build + tests + Android lint
```

The debug build carries `applicationIdSuffix = ".debug"` so it coexists with a future
release build on one device — the developer's real rides can live in the release build while
experimental debug builds are installed alongside.

## Install on a device

`installDebug` builds and installs in one step. One-time phone setup: **Settings → About phone
→ tap "Build number" seven times** to unlock Developer options, then enable **USB debugging**;
plug in via USB and accept the "Allow USB debugging?" prompt.

```powershell
adb devices                   # confirm the phone is connected
./gradlew :app:installDebug   # build + install the debug APK
```

The app appears in the launcher as **"Trailog"**; its debug package id is
`io.github.martinzitka.trailog.debug`. To install a prebuilt APK manually:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Wireless option: after the first USB connect, `adb tcpip 5555` then
> `adb connect <phone-ip>:5555` runs the commands below without a cable.

## Recording (on device)

The **Record** tab drives the recording engine. From a cold start:

1. Grant **precise location + notifications** when prompted.
2. Set location to **"Allow all the time"** — this is what makes recording resume after a phone
   restart (a location foreground service cannot start from `BOOT_COMPLETED` on Android 14+
   without background location). The screen explains this and deep-links to system settings.
3. Exempt the app from **battery optimisation** when the warning appears — OEM power management
   will otherwise kill long recordings.
4. Pick an activity type and **Start**. Stop requires confirmation (an accidental stop loses a
   ride). An interrupted activity is surfaced on next open with the gap length and Resume/Finish.

Recording runs in a foreground service and continues with the screen off, the app backgrounded,
or the app swiped from recents. The screen is **not** kept on while recording (see
[ADR 0011](docs/adr/0011-record-screen-keep-on.md)).

> **Recording changes cannot be validated in an emulator or by automated tests** — they need a
> real outdoor run on the device. See `CLAUDE.md` ("Android specifics").

### Durability kill-matrix (developer)

Run these with the phone connected while a recording is in progress. `PKG` is the **debug**
package id. In PowerShell:

```powershell
$PKG = "io.github.martinzitka.trailog.debug"

adb shell am force-stop $PKG           # user-style kill; service restarts, new segment
adb shell am crash $PKG                # uncaught exception
$pid = adb shell pidof $PKG            # hard process kill
adb shell run-as $PKG kill -9 $pid
adb reboot                             # device restart with an activity in progress
```

After each, reopen the app and confirm the fix count kept climbing (no lost points before the
interruption) and a **new segment** began. Swiping from recents must **not** stop recording.
Draining the battery to shutdown and rebooting is a manual, one-time test — it cannot be scripted.

## License

TBD.
