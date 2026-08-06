# 8. Recording session state machine and its `:core` home

Date: 2026-08-06

## Status

Accepted

## Context

M1.3 builds the recording engine. CLAUDE.md constrains it in three ways that shape the
design before any Android code is written:

- The recording engine "sits behind an interface; the Android foreground service is one
  implementation" — so iOS stays possible and the engine can be reasoned about off-device.
- Session state "lives in the database, never in memory or SharedPreferences" — so any
  process (a cold-started app, a boot receiver) can read what was happening.
- No statistic ever interpolates across a segment boundary, and "every recovery starts a
  **new segment**".

The recovery *policy* — the gap-length branches — was fixed in ADR 0004 and CLAUDE.md's
recovery table. What was not yet pinned down: the state machine's shape, where it lives,
and how a **manual pause** interacts with segments.

## Decision

**The state machine, session model and recovery policy live in `:core`, platform-free.**
`RecordingEngine` is an interface in `:core`; the Android foreground service will implement
it in `:app`. The pure pieces — `RecordingStateMachine`, `RecordingSession`,
`RecoveryPolicy` — carry no Android, Ktor or file-I/O types and are unit-tested exhaustively
without a device. This is the part of M1.3 that is verifiable off-hardware, so it is built
first; the service wiring and the on-device kill-matrix follow.

**Five states, one total transition function.** `IDLE, RECORDING, PAUSED, STOPPING,
RECOVERING`, exactly as the plan lists them. Every (state, command) pair either maps to one
next state or is rejected with `IllegalRecordingTransition`; both branches are tested. The
legal transitions are:

```
IDLE       + START              -> RECORDING
IDLE       + DETECT_INTERRUPTED -> RECOVERING
RECORDING  + PAUSE              -> PAUSED
RECORDING  + REQUEST_STOP       -> STOPPING
PAUSED     + RESUME             -> RECORDING
PAUSED     + REQUEST_STOP       -> STOPPING
STOPPING   + FINALIZE           -> IDLE
RECOVERING + RECOVER_RESUME     -> RECORDING
RECOVERING + FINALIZE           -> IDLE
```

**A manual pause/resume opens a new segment, exactly like a recovery.** This is the
non-obvious call. While paused, no fixes are recorded (it also saves battery), so a
pause/resume produces a real gap in the fix stream — often a long one, e.g. a lunch stop.
If resume reused the same segment index, every segment-aware statistic would treat the
pre-pause and post-pause fixes as contiguous and draw a straight line across the gap,
inventing distance and elevation. Incrementing the segment index on resume makes the gap
explicit, so the existing segment-aware statistics handle it for free with no special case.
Recovery resume does the same thing. There is therefore one rule — *recording resumes into
a new segment, always* — not two.

**Recovery-decision boundaries are made exact.** CLAUDE.md gives approximate thresholds
("under ~15 min", "~15 min to ~3 h", "over ~3 h"). The code fixes them as
`AUTO_RESUME_MAX = 15 min` (exclusive lower boundary → a gap of exactly 15 min prompts) and
`FINALIZE_MIN = 3 h` (inclusive → exactly 3 h finalises). A negative gap, which a jumping
device clock can produce (CLAUDE.md: clocks jump), is coerced to zero and resumes — erring
towards preserving the ride, per the asymmetry in ADR 0004. A session that recorded no fix
at all resumes rather than being abandoned.

## Consequences

- The correctness-critical logic is proven by unit tests (22 across three classes) before a
  service exists, keeping the on-device work to wiring and the kill-matrix.
- "Resume always opens a new segment" is now a single invariant covering both pause and
  recovery, which is easy to state and to test.
- The `RecordingEngine` interface is provisional: it has no implementation yet, so it may
  gain or lose methods when the Android service and the M1.5 Record-screen ViewModel are
  built. The pure state machine, session model and recovery policy are stable regardless.
- The persisted `RecordingSession` fields (`lastFixTime`, `heartbeat`, `currentSegmentIndex`)
  define what the M1.4 Room `recording_sessions` table must store.
- This ADR refines, and does not supersede, ADR 0004: 0004 owns the recovery *policy*; this
  ADR owns the state machine and the pause/segment decision.
