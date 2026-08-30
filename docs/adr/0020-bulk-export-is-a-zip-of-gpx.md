# 20. Exporting everything writes one zip of GPX files, streamed a ride at a time

Date: 2026-08-30

## Status

Accepted. Extends the per-activity export shipped with M1.5 screen 3; supersedes nothing.

## Context

M1.7 asks for GPX export "single activity and all activities". The single-activity half already
exists on Activity detail: the ViewModel builds a GPX string through `:core`'s `Gpx`, the screen
opens a destination with `ActivityResultContracts.CreateDocument` and writes to it. The remaining
half is the whole history at once — hundreds of rides now, and the Sports Tracker import (M2) will
multiply that.

Three things had to be settled: what the output is, where the entry point lives, and how the write
is bounded.

There was also dead code in the way. `recording/GpxExport.kt` wrote a track straight into
`Downloads/` via MediaStore. It was an M1.3 test-harness affordance, its own KDoc said so, and
nothing has referenced it since the real export shipped. It is deleted rather than repurposed —
the app should not write to storage it chose for itself when the user can choose.

## Decision

**One zip containing one GPX per activity, to a destination the user picks.** The alternative was
`OpenDocumentTree` and N loose files in a folder. A zip wins on the things that matter here: one
SAF round trip instead of hundreds, one destination the user can move off the phone in one action,
no half-populated folder to reason about when a write fails, and no collision with whatever the
folder already held. It is also what the apps a user is migrating *from* hand them, so the shape is
already familiar. The cost is that the archive must be unzipped before a GPX reaches another tool,
which is a step every desktop and phone can do without an app.

**Streamed one ride at a time.** `GpxArchive` takes a worklist of `ActivityRef` — id and start time,
projected by the DAO — and loads each ride's raw points only when it is about to write them. Peak
memory is therefore the largest single ride, not the sum of the history. Reading whole
`ActivityEntity` rows first would be harmless at today's volume and wrong at 500 activities; the
projection exists so the wrong version is not the easy one to write.

**Serialisation goes through `:core`'s `Gpx`, the same call the per-activity export makes.** There
is no second writer and no bulk-specific fast path, so an archived ride is byte-identical to the
same ride exported on its own — segments as separate `<trkseg>` elements, Trailog extensions
intact, and a recording gap never welded into a straight line.

**The entry point is a Data group in Settings.** History is left as a list. Settings is where the
rest of the data rights will land — full export and full deletion are product features, not
afterthoughts (CLAUDE.md's legal note) — and grouping them is worth more than putting the action
next to the rows it reads. The group is the one place in Settings that holds an action rather than
a setting, which the screen's KDoc says out loud so the "only settings that something reads" rule
is not quietly bent.

**Progress and outcome are separate ViewModel state, not a snackbar.** `ExportViewModel` is its own
class rather than fields on `SettingsViewModel`, which is a thin projection of stored preferences
with no I/O at all. Exporting several hundred rides takes long enough that a silent button reads as
broken, and a transient snackbar would take the result away with it, so the row shows Idle,
Running(done, total), Done(count) and Failed inline.

**File naming stays at the display edge.** The screen passes an `entryName` lambda built from
`Formatter.exportFileName`, the same function the detail screen's export uses. The archive writer
never formats anything; it only disambiguates, appending `-2`, `-3` when two rides would produce
the same name.

## Consequences

- **An export is a read and nothing else.** `ExportViewModel` is given a worklist and a loader —
  there is no write path it could take even by mistake — so a failure needs no recovery: the worst
  case is a partial file at a destination the user chose. A test asserts this shape.
- **A ride deleted mid-export is skipped, not fatal.** The worklist is a snapshot; a row that has
  gone by the time its turn comes was deleted on purpose.
- **Failures carry no detail to the screen.** The realistic causes are a stream that could not be
  opened or written, and the storage layer has already told the user. Nothing is logged either —
  an export touches every coordinate in the database, which is the last thing that should reach a
  log that gets pasted into a bug report.
- **An empty history still writes a valid, empty archive.** The picker has already created the file
  by the time the count is known, so the export completes and says "nothing to export" rather than
  failing over a file it did not create.
- **Duplicate names are disambiguated, not overwritten.** Two rides recorded in the same minute with
  the same name are otherwise one file. Silently losing a ride from a full-history export would be
  the worst possible failure mode of this feature.
- **No progress notification and no work manager.** The export runs in `viewModelScope`, so it
  outlives the composition — rotation, or scrolling the row off screen, does not disturb it — and
  ends when the Settings back-stack entry's ViewModel store is cleared. It does not survive the
  process being killed. A foreground service for a user-initiated read would be a lot of machinery
  for a job measured in seconds, and rerunning it is cheap and repeatable. If the imported Sports
  Tracker history makes this slow enough to matter, that is the point to revisit it.
- **Not verified against a real history yet.** The tests run against fabricated rides; the timing
  claim above is an expectation, not a measurement. It wants a run against the device's actual
  recordings — and, once M2 lands, against the imported archive.
