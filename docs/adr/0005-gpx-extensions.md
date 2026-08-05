# 5. GPX carries Trailog's non-core fields under an extension namespace

Date: 2026-08-05

## Status

Accepted

## Context

`:core` gets one GPX reader and one GPX writer, shared by the phone, the server and the
importer (CLAUDE.md: a track is never parsed two ways). GPX is also a stated data-portability
format (M1.7 export) and the format the M1.2 fixtures and reference tracks arrive in.

GPX 1.1's `<trkpt>` natively expresses only latitude, longitude, `<ele>` and `<time>`. A
Trailog [`RawPoint`] additionally carries `accuracy`, `speed`, `bearing` and `pressure` — all
delivered by the OS and, under "raw points are sacred, lose nothing", not discardable. A naive
mapping to core GPX would silently drop them, so a phone activity exported to GPX and read back
would not equal the original. M1.2's acceptance criteria demand a round-trip with **no data
loss**, including segment structure.

## Decision

- Write the four non-core fields under a Trailog XML namespace, `urn:trailog:gpx:v1`
  (`Gpx.TRAILOG_NS`), inside each `<trkpt>`'s `<extensions>` element. Only fields that are
  actually present are emitted.
- Use a **URN**, not a URL, as the namespace identifier. It is an opaque identifier; making it
  a URL would invite the impression of a fetchable resource, and this project does not make
  network calls to domains the user does not control (CLAUDE.md).
- `<trkseg>` boundaries map to `RawPoint.segmentIndex` and are preserved on read and write, so
  a gap never becomes one continuous line.
- The reader is a **tolerant reader**: it matches by local element name (so GPX 1.0/1.1 and
  namespaced variants all parse), reads core fields from any source, reads the Trailog
  extensions when present, and ignores foreign extensions (e.g. Garmin heart-rate). A plain
  third-party GPX parses with the absent fields left null.
- The reader disables external entities and DTD processing (XXE hardening).

## Consequences

- A phone-recorded activity round-trips through GPX losslessly, satisfying the M1.2 criterion,
  while the output stays valid GPX 1.1 that any third-party tool opens (it ignores the
  extensions it does not recognise).
- Interop is one-directional for the extra fields: other tools will not populate
  accuracy/speed/bearing/pressure, so a track imported from Strava or Sports Tracker simply has
  those null. That is correct — we cannot invent data the source did not record.
- GPX's free-text `<trk><type>` is **not** mapped to the [`ActivityType`] enum inside the
  parser; `GpxTrack.type` stays a raw string and the caller resolves it. Baking a lossy
  guess into the single shared parser would be worse than making the boundary explicit.
- FIT read/write (also M1.2) is a separate implementation; this ADR covers GPX only.
