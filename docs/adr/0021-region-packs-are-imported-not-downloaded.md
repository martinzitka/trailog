# 21. Region packs are imported from a file, never downloaded by the app

Date: 2026-08-30

## Status

Accepted. Completes the offline half of M1.6 Maps; ADR 0015 still governs map sources and egress.

## Context

The PMTiles archive cannot ship in the APK — the Czech build is 1.3 GB against Play's 150 MB base
APK cap — so it lives in the app's external files directory. Until now it got there exactly one
way: `adb push`. That is fine for the developer and useless for anyone else, and worse, the app
said nothing about it. `MapTiles.findArchive` picked the largest `.pmtiles` file it could find and
`RouteMap` fell back to drawing the route over a blank background when there was none. A phone with
no archive, a phone with a truncated archive, and a phone where the tiles directory had been
created by the shell user and was unreadable all produced the same screen.

M1.6 calls for "PMTiles region packs downloadable to device storage". The word *downloadable* is
the part that needed deciding.

## Decision

**The app imports a file the user picked. It does not download anything.**

`ActivityResultContracts.OpenDocument` with a `*/*` filter — `.pmtiles` has no registered MIME
type, so a narrower filter would hide the very file the user came for. The archive is then *copied*
into the app's own directory, not referenced: a `content://` uri is not a path MapLibre can open,
the permission grant behind it does not survive a reboot, and the user is entitled to delete the
file they imported from. Installed means present, offline, permanently.

Not downloading is the whole privacy argument, made structurally rather than by policy. There is no
URL field, no host to allowlist, no partial-download resume, and no way for a future change to
quietly acquire a default server. A user fetches the archive from their own server with whatever
browser or file manager they already use, and hands the file to Trailog. CLAUDE.md's rule about
egress is satisfied by there being no network code at all.

**A new `MapArchiveStore` owns the directory; `MapTiles` keeps only format knowledge.** The store
holds the installed list and the active archive as `StateFlow`s and is a process-wide singleton, so
`RouteMap` observes it rather than calling `findArchive` inside a `remember`. That is what makes an
import appear on the Record map and the Activity maps without restarting the app.

**The active archive is a stored preference, falling back to the largest installed.** The fallback
preserves the adb workflow exactly — push a file, it is used, no preference needed — while the
preference means a user carrying Czechia and Austria picks rather than accepts whichever is bigger.
A choice naming an archive that is no longer installed is ignored, so a stale preference can never
blank the map.

**The header is validated after 127 bytes, before the copy.** A PMTiles v3 header is a fixed
struct with a magic and a version, so picking a holiday photo costs no time and no storage. The
same 127 bytes then yield the coverage bounds shown on the row.

**The copy goes to a `.part` file that the scan ignores, renamed only when whole.** This is the
load-bearing detail. The fallback picks the *largest* file, so a half-copied country build would
win that contest and render as a corrupt archive. Cancelling or failing deletes the partial file.

## Consequences

- **The adb push still works and is still documented.** `infra/tiles/README.md` now describes both
  routes. The app validates on import; adb does not, so a truncated push still presents as an
  archive that renders nothing. That asymmetry is worth the convenience during development.
- **A 1.3 GB copy takes real time on a phone.** Progress is shown in bytes with a cancel button,
  and the import runs in `viewModelScope` — it survives rotation but not leaving the screen or the
  process dying. A `WorkManager` job would survive both, and is the right answer only if this turns
  out to be slow enough to walk away from. **Unmeasured: no device was attached when this was
  written.**
- **Free space is checked only when the picker declares a size**, which it is not obliged to do.
  Where it does, an archive that cannot fit is refused before a byte is written; where it does not,
  a full disk surfaces as an ordinary copy failure with the partial file removed.
- **Imported names are kept as the user's, sanitised rather than normalised.** Path separators,
  reserved characters, control codes and whitespace runs are replaced; letters in any script
  survive, because this name is what the screen lists and a pack called "Česko" should not appear
  as "esko". A collision appends `-2`, so importing twice keeps both rather than overwriting.
- **Removing a pack is confirmed and irreversible.** The dialog says so: Trailog cannot re-download
  it, and rebuilding a country archive is a twenty-minute job on a desktop.
- **Nothing here is verified on a device.** The tests drive real files in a temp directory and the
  screen under Robolectric, which covers the store and the states — but the file picker is a system
  activity, and whether MapLibre picks up a swapped archive without a restart has never been seen.
  Both need the phone.
