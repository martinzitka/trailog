# 18. Naming a ride happens after it is saved, in a dialog on the Record screen

Date: 2026-08-30

## Status

Accepted

## Context

M1.8 asks for the one thing the metadata story was missing: a prompt to name a ride at the moment
you finish it, while you still remember where you went. Everything downstream already existed —
`activities.name` and `activities.notes` are in the schema, `ActivityDao.updateMetadata` writes
both with an `updatedAt` bump for the sync tie-breaker, and Activity detail has an edit dialog for
them. No migration was needed and none was made.

The open question was *where* the prompt lives. Two shapes were considered:

1. **Navigate to the finished activity's detail screen with the existing `EditDialog` already
   open.** Reuses the dialog verbatim and shows the ride's stats and map underneath, which is a
   pleasant thing to see after a ride.
2. **A dialog in place on the Record screen**, over whatever the screen shows next.

A third consideration cuts across both: whether the prompt should appear *before* or *after* the
activity is saved. CLAUDE.md's "lose nothing" makes that the load-bearing decision, not an
implementation detail.

## Decision

**The activity is finalised first, and the prompt is a dialog on the Record screen afterwards.**

`RecordViewModel.stop()` calls `engine.requestStop()` and `engine.finalize()` and only then raises
a `NamingPrompt`. By the time any UI appears, the ride is on disk and the recording session row is
gone.

This makes "skipping must save the activity anyway" structural rather than a rule someone has to
remember. Every exit from the dialog is identical in effect — Save, Skip, a tap outside, the back
button, the process being killed mid-typing — because none of them is on the path that saves the
ride. There is no ordering a future edit could get wrong, and nothing to test for beyond the write
itself.

Option 1 was rejected on coupling, not aesthetics. It would add a Record → detail navigation edge
whose only purpose is metadata, leave the user off the Record screen when the common next action is
to start another ride, and put back-stack behaviour between a finished recording and its name.

The prompt is deliberately **not** a `RecordUiState` case. It outlives the recording: when it
appears the session is already null and `uiState` has fallen back to `Ready`. It is a separate
`StateFlow<NamingPrompt?>` on the ViewModel, which also means a rotation mid-typing keeps it.

**The name field starts empty.** History already derives a display title from the type and date for
an unnamed activity, and pre-filling with that derived string would put a formatted value into the
database — exactly what ADR 0014 forbids. The type label is offered as *placeholder* text instead:
a hint, never a value. For the same reason, a prompt dismissed with both fields blank writes nothing
at all rather than stamping a pointless `updatedAt` on the row.

Note what the placeholder does *not* do. Material 3 shows an unfocused empty field's label centred
inside it and reveals the placeholder only once the field takes focus, so the type label is not
visible when the dialog first appears — confirmed on the device. The prose in the dialog body
carries that job instead ("or skip, and Trailog will list it by type and date"); the placeholder is
a bonus for anyone who taps in, not the mechanism.

Finishing an interrupted session from the recovery prompt (`recoverFinish`) offers naming on the
same terms. That ride may be hours old, but the prompt costs one dismissal and the alternative is
it landing in History unnamed and unnoticed.

## Consequences

- The naming dialog is a second, simpler dialog alongside Activity detail's `EditDialog`: name and
  notes only, no activity type. The type was chosen before recording started and re-asking at the
  finish line is noise; it stays editable on Activity detail.
- `RecordViewModel` gained a `saveMetadata` lambda, injected the same way `loadActivity` already
  was, so the ViewModel stays free of Android and Room types.
- `ActivityRepository.updateMetadata` calls `recompute`, so naming a ride rebuilds its cached
  statistics moments after finalisation already did. The inputs are identical raw points and the
  writes are idempotent upserts, so this is wasted work rather than a race — sub-second on a
  four-hour ride, and not worth a second code path to avoid.
- Filtering on name or notes later needs nothing now. Activity counts are in the hundreds, so a
  `LIKE` scan is adequate and an index cannot help a leading-wildcard search. FTS5 stays a later
  migration if real search is ever wanted.
