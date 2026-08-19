# 13. The Sensors screen reads the hardware directly, and never writes a point

Date: 2026-08-17

## Status

Accepted

## Context

M1.5 screen 4 must show live GPS accuracy, satellite count, fix age and provider, a
barometer reading or an explicit "not available on this device", and the recording
service's and permissions' state — all updating while the screen is open.

Two of those requirements pull in opposite directions from the existing code:

- **"Live" is not conditional on recording.** The single most useful moment for a
  diagnostics screen is *before* pressing Start, standing outside wondering why the fix is
  poor. So the readings must exist when nothing is being recorded.
- **The only existing source of this data is the recorder.** `RecordingDiagnostics` is a
  process-wide object the foreground service fills as fixes arrive. It is populated only
  while the service runs, carries no fix timestamp, and exists to feed the ongoing
  notification.

Three options were considered:

1. **Read `RecordingDiagnostics` only.** Cheapest, but the screen is empty unless an
   activity is recording — which fails the requirement and removes most of its value.
2. **Start the recording service to power the screen.** Absolutely not: a diagnostics
   screen must never create a recording session, and the service's whole purpose is
   writing raw points.
3. **Give the screen its own read-only listeners.** More code, satisfies the requirement.

## Decision

**The Sensors screen owns a `SensorProbe` that registers its own `LocationListener`,
`GnssStatus.Callback` and barometer listener for as long as the screen is collecting, and
writes nothing.**

- `AndroidSensorProbe` is registered on collection and unregistered on cancellation, and
  the ViewModel's state flow is `WhileSubscribed`, so leaving the tab releases GPS. A
  diagnostics screen that quietly kept the receiver alive would be a battery bug.
- **The screen collects with `collectAsStateWithLifecycle`, not `collectAsState`.** This is
  not a stylistic preference. Backgrounding the app does not dispose the composition, so a
  plain `collectAsState` keeps a subscriber alive, `WhileSubscribed` never fires, and the
  receiver keeps running behind the home screen. That was measured on device — `dumpsys
  location` showed the registration alive and fixes still arriving after the app was
  backgrounded — and it is precisely the battery bug above. Any future screen that holds
  hardware open must collect lifecycle-aware for the same reason.
- The probe runs **whether or not a recording is in progress**. Registering a second
  location listener alongside the service's is deliberate: the platform multiplexes both
  onto one GPS session, so the screen observes the same hardware without disturbing the
  recording and without a second write path.
- **The probe never touches the database.** Raw points are written by the recording
  service alone (CLAUDE.md: "Raw points are immutable and sacred"). A fix delivered to the
  Sensors screen is displayed and discarded. There is exactly one ingest path, and it is
  the service.
- `RecordingDiagnostics` stays as it is, feeding the notification. It is not the screen's
  source; the two are independent observers of the same hardware.
- **`SensorSnapshot` carries no coordinate.** Accuracy, satellite counts, provider name,
  fix age and pressure describe signal health completely without saying where the phone
  is. This is not incidental: a diagnostics readout is precisely the screen a user
  screenshots into a bug report, and CLAUDE.md keeps positions out of that path.

The screen is modelled as a list of typed `DiagnosticRow`s grouped into cards, with SI
values (metres, seconds, Pascals) formatted only at the display edge through `Format`.
Adding a sensor later — a BLE heart-rate strap, a cadence sensor — is a new `DiagnosticId`,
a branch in the label and value mappings, and a string resource. The layout does not
change, which is the M1.5 criterion "adding a new sensor type later requires adding a row,
not restructuring the screen".

## Consequences

- Opening the Sensors tab turns on GPS. That is the point of the screen, but it means the
  tab is not free to visit — the subtitle says readings are live while the screen is open,
  and the probe stops when it is not: on leaving the tab, and a few seconds after the app is
  backgrounded or the screen is locked.
- Health thresholds (accuracy, fix age, satellites used) live as named constants on
  `SensorsViewModel` and are unit-tested. They are display hints only; nothing in the
  recording or statistics path reads them.
- The screen shows the durable session state *and* whether the foreground service is
  actually alive in this process, and flags the disagreement. A session that believes it is
  recording while the service is dead is the failure mode this screen exists to catch, and
  it is the one pairing rendered as a warning.
- Because the probe is an interface, the ViewModel is tested against scripted snapshots and
  needs no device. The probe implementation itself is not unit-tested — it is platform
  registration code, and per CLAUDE.md sensor behaviour must be confirmed outdoors on real
  hardware, not on an emulator.
