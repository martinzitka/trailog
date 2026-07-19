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
on the actual device.

### Step 1 — Install the debug build on your phone

One-time phone setup: **Settings → About phone → tap "Build number" seven times** to unlock
Developer options, then **Settings → System → Developer options → enable "USB debugging"**.
Plug the phone into this machine with a USB cable.

`adb` lives in the Android SDK (`platform-tools`); it is on this machine at
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. From the repo root:

```powershell
# Confirm the phone is connected (accept the "Allow USB debugging?" prompt on the phone).
adb devices

# Build and install the debug APK directly onto the connected phone in one step:
./gradlew :app:installDebug
```

`installDebug` builds and installs in one go. If you prefer to install a prebuilt APK
manually (e.g. copied to the phone), use:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The installed app appears in the launcher as **"Trailog M0"**; its package id is
`io.github.martinzitka.trailog.debug`.

> Wireless option: after the first USB connect, `adb tcpip 5555` then
> `adb connect <phone-ip>:5555` lets you run the kill-matrix commands below without a cable.

### Step 2 — Grant permissions and start recording

In the app:

1. Grant **foreground location + notifications** (button 1).
2. Grant **background location** — "Allow all the time" (button 2, or app settings).
   This is what makes reboot recovery possible; without it a location foreground service
   cannot start from `BOOT_COMPLETED` on Android 14+.
3. Grant the **battery-optimisation exemption** (button 3).
4. **Start recording**, pocket the phone, screen off.

### Step 3 — The kill matrix (durability tests)

Run these with the phone connected via `adb` while a recording is in progress. `PKG` is the
**debug** package id. In PowerShell:

```powershell
$PKG = "io.github.martinzitka.trailog.debug"

adb shell am force-stop $PKG           # user-style kill; service should restart, new segment
adb shell am crash $PKG                # uncaught exception
$pid = adb shell pidof $PKG            # hard process kill
adb shell kill -9 $pid
adb reboot                             # device restart with an activity in progress
```

After each, reopen the app and confirm the fix count kept climbing (no lost points before
the interruption) and a **new session/segment** began. Swiping the app from recents must
**not** stop recording. Draining the battery to shutdown and rebooting is a manual,
one-time test — it cannot be scripted.

### Acceptance criteria to check off (from `IMPLEMENTATION_PLAN.md` M0)

- [ ] Two outdoor activities ≥ 2 h each; at least one in poor/no mobile signal.
- [ ] No gap in the point stream longer than 30 s.
- [ ] Survives switching apps and device idle.
- [ ] `am force-stop` mid-recording loses no more than a few seconds.
- [ ] `adb reboot` mid-recording: prior points survive; service restarts from boot.
- [ ] Swiping from recents does **not** stop recording.
- [ ] Battery drained to shutdown, recharged, booted — points before shutdown survive.
- [ ] Exported GPX (button "Export GPX to Downloads", lands in the phone's Downloads folder)
      opens correctly in a third-party tool and the track looks right.
- [ ] Battery drain over a 2-hour recording measured and written down.

Record findings; **if M0 fails, do not proceed to M1** — diagnose first (see the plan's
fallback order).

## License

TBD.
