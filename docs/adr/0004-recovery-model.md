# 4. Recovery model: data-loss-free reboot is mandatory; automatic restart is best-effort

Date: 2026-07-20

## Status

Accepted

## Context

CLAUDE.md frames reboot recovery as a functional requirement and, from it, derives that
"Allow all the time" (`ACCESS_BACKGROUND_LOCATION`) is required so a location foreground
service can be launched from a `BOOT_COMPLETED` receiver on Android 14+.

M0 tested this on the developer's actual device (Motorola edge 40, Android 15) across two
reboots taken mid-recording:

- **Data survival was total, both times.** Every point recorded before the reboot
  persisted; a reboot mid-ride costs nothing. This is the `PRAGMA synchronous = FULL` +
  write-on-arrival design working as intended.
- **Automatic restart was unreliable.** The first reboot did not restart recording (cause
  unknown — the receiver was not yet instrumented). The second, after adding a persistent
  boot breadcrumb, restarted cleanly: the receiver fired, background location was honoured,
  the location FGS started from boot, and it recorded into a new segment. So the platform
  and the OEM *permit* boot-restart on this device, but delivery is not guaranteed — which
  matches the well-known flakiness of OEM boot broadcasts.

The developer decided that automatic restart is not required, provided (a) a reboot never
loses data, and (b) recording can be restarted from the app afterwards. Both already hold.

## Decision

- **Hard requirement:** a reboot, crash, or process kill must never lose a point recorded
  before it. This is non-negotiable and is met.
- **Guaranteed recovery path is manual and app-surfaced.** On next app open, an interrupted
  session is detected and the user is offered Resume / Finish (the M1.5 Record screen's
  "interrupted-session-found" state and the gap-based policy in CLAUDE.md). Recovery always
  opens a **new segment**; it never appends to the previous one.
- **Automatic restart from `BOOT_COMPLETED` is best-effort**, attempted where the OS and OEM
  permit it, but never relied upon for correctness. Its failure degrades to the manual path,
  not to data loss.
- `ACCESS_BACKGROUND_LOCATION` is still requested — it is what enables the best-effort
  auto-restart and other background behaviour — but it is no longer a hard *functional
  requirement for recovery*. The permission flow should explain it honestly as "lets
  recording resume by itself after a restart" rather than implying recovery is impossible
  without it.

## Consequences

- M1.3 does not need to fight OEM auto-start managers to be correct; that work becomes an
  optional reliability improvement, not a blocker.
- The user is informed a reboot happened (via the interrupted-session prompt) rather than
  silently resumed — arguably better UX for a tracker.
- This supersedes the stronger "reboot recovery is a functional requirement / Allow all the
  time is a functional requirement" reading in CLAUDE.md. CLAUDE.md's prose is not edited
  here; this ADR is the current position.
- The boot breadcrumb (`files/boot.log`) and the instrumented `BootReceiver` live only in
  the throwaway M0 spike. M1.3 reimplements recovery properly behind the `RecordingEngine`
  interface.
