# 2. Use the platform LocationManager, not Google's fused location provider

Date: 2026-07-19

## Status

Accepted

## Context

The recorder needs GPS fixes at roughly 1 Hz plus, for diagnostics, satellite counts. Two
Android APIs deliver location:

- **Google Play Services `FusedLocationProviderClient`** — higher-level, fuses GPS with
  network and sensor hints, generally smoother tracks. Ships in `play-services-location`,
  a proprietary Google dependency, and abstracts away raw GNSS detail such as satellite
  count.
- **Platform `android.location.LocationManager`** — part of AOSP, no Google dependency.
  Exposes the raw GPS provider, `GnssStatus` (satellite counts, used-in-fix flags) and NMEA.

Trailog is privacy-first: no third-party egress, no Google Maps SDK, and a preference for
software the user fully controls (CLAUDE.md). It also must run on de-Googled devices
(GrapheneOS, /e/OS) that many privacy-conscious users run. M0's diagnostic screen
explicitly wants satellite count, which fused location does not surface.

## Decision

Use the platform `LocationManager` with the GPS provider, `GnssStatus.Callback` for
satellite diagnostics, and `SensorManager` (`TYPE_PRESSURE`) for the barometer. Do not add
a dependency on Google Play Services.

This applies to the M0 spike and is the intended direction for the real M1 recording
engine. The engine sits behind a `RecordingEngine` interface (CLAUDE.md: keep iOS
possible), so the concrete provider can be swapped without touching callers if fused
location ever proves necessary.

## Consequences

- Zero proprietary location dependency; works on de-Googled devices.
- Raw GNSS detail (satellite count, accuracy) is available for the diagnostics screen.
- We give up fused location's built-in smoothing. This is acceptable and in fact desirable:
  raw points are sacred and filtering/smoothing is a separate, recomputable `:core` stage
  (CLAUDE.md). We do not want the provider silently pre-smoothing what we persist.
- We are responsible for our own duty-cycling and battery behaviour rather than inheriting
  fused location's tuning. M0 measures battery drain to validate this.
