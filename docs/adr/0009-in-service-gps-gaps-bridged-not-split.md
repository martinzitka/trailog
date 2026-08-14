# 9. In-service GPS gaps are bridged within a segment, not split

Date: 2026-08-14

## Status

Accepted

## Context

CLAUDE.md lists "a signal blackout" alongside a crash, a process kill and a reboot as an
event that "ends one segment and begins another", and warns never to interpolate across a
segment boundary.

M1.3's recorder creates a new segment only on events that actually interrupt recording: a
process death, a reboot, a manual pause/resume, and recovery (all routed through the session
state machine, ADR 0008). It does **not** start a new segment merely because fixes stop
arriving while the foreground service keeps running.

Five real outdoor rides (196 km, up to 6.75 h) exercised this. They contained a handful of
**in-service GPS gaps** — the app and the location service alive and recording, but no fix
delivered for tens of seconds up to ~3 minutes (a 189 s gap on one ride). Those gaps stayed
inside a single segment, so the statistics layer connects the fixes either side of them with
a straight line. The question this raised: should the recorder split a segment when a fix
arrives after a long in-service gap, to honour CLAUDE.md's "signal blackout ends a segment"?

## Decision

**No. In-service GPS gaps are bridged within the segment; they do not create a new segment.**
Segment boundaries are produced only by events that genuinely stop recording — process death,
reboot, and manual pause/resume — never by the mere absence of fixes while recording
continues.

Rationale:

- **Bridging is closer to the truth than splitting for a moving rider.** A live recording
  that loses signal in a tunnel, dense forest or urban canyon usually means the rider still
  covered that ground. A straight-line chord across the gap approximates it — an
  *underestimate*, since the chord is no longer than the true path. Splitting the segment
  would instead drop that traversal from the distance entirely, which is further from the
  truth. Keeping the distance, even as a straight line, is the wanted behaviour.
- **The dangerous case is handled by the speed filter, not by segmentation.** The real risk
  is a GPS glitch that teleports a fix far away after a gap, injecting phantom distance.
  `SpeedFilter` already guards this: a resumed fix implying more than the activity type's
  `maxPlausibleSpeed`, averaged over the gap, from the last good fix is discarded, and
  scanning continues from that last good fix. So an implausible teleport is dropped while a
  plausible tunnel traversal is kept and counted.
- **Deliberate long stops are already covered.** A rider who stops for a break either pauses
  (which *does* open a new segment), or leaves recording on — in which case they are
  near-stationary and the bridged chord is negligible. In the field data every in-service gap
  over 30 s coincided with the rider being essentially still: the 189 s gap spanned 51 m, and
  all such gaps across 196 km totalled ~0.1 km of chord (~0.05 %).
- **Moving time is unaffected.** It is gated by the per-type speed threshold, so a long parked
  stint contributes little or no moving time regardless of how the segment is drawn.

## Consequences

- The recorder stays simple: the segment index advances only on explicit start-new-segment
  events (pause/resume, recovery), never on a timer or a gap heuristic. There is no new
  threshold constant to tune, and no risk of fragmenting a legitimate tunnel descent into
  many tiny segments.
- Statistics remain segment-aware — they never bridge across a true segment boundary — but
  they **do** integrate across in-service gaps *within* a segment, intentionally.
- This refines CLAUDE.md's "a signal blackout ends one segment and begins another": in
  practice a blackout that leaves the recording process running does *not* end a segment; only
  an interruption that stops recording (or a manual pause) does. CLAUDE.md's prose is not
  edited here; this ADR is the current position, as ADR 0004 did for reboot recovery.
- If a genuine need for a safety split ever appears — for example a very long, high-speed
  blackout where even the average-speed check would pass — it is a localized addition in the
  engine's fix-recording path (open a new segment when a fix arrives after a gap beyond some
  large threshold). It is deliberately not done now: there is no evidence it is needed, and it
  would risk splitting the very traversals this decision means to preserve.
