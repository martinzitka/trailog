# 11. Recording does not force the screen on

Date: 2026-08-15

## Status

Accepted

## Context

The M1.5 Record screen acceptance criteria include: "Screen-on behaviour while recording
matches the documented decision." No such decision existed, so this ADR sets it.

Recording runs entirely in the foreground service (CLAUDE.md: "Recording runs in a
foreground service … the only sanctioned mechanism"). The service produces and persists
fixes regardless of whether any Activity is in the foreground or the screen is on. The
Record screen is a *view* over that service's durable state, not a participant in recording.

Some trackers hold the screen on for the whole activity (a handlebar-mounted phone showing
live stats). Others let the screen sleep and rely on the notification and the service. The
question is which Trailog does by default.

## Decision

**Recording does not keep the screen on.** The Record screen sets no
`FLAG_KEEP_SCREEN_ON` / `keepScreenOn` modifier. The screen may sleep freely while an
activity records; the foreground service keeps recording, and the ongoing notification
remains the always-available status surface.

Rationale:

- **Battery.** Trailog records multi-hour rides (a 6.75 h ride already in the fixture set).
  Holding a bright OLED/LCD panel on for hours is one of the largest avoidable drains on the
  device, and battery survival to the end of a long activity is a core durability goal
  (CLAUDE.md's recovery section, and the battery-optimisation exemption exist for exactly
  this reason). Defaulting the screen on would work against that.
- **Recording does not depend on the screen.** Because the service owns recording, screen
  state is purely a display concern. There is no correctness reason to keep it on, so the
  cheaper default wins.
- **The notification already covers "is it recording?"** A user who locks the phone and
  pockets it can confirm recording from the persistent notification without waking the app.

## Consequences

- The Record screen is a plain screen with respect to power: no wake flag, nothing to
  restore across configuration change on that axis.
- A future **opt-in** setting ("keep screen on while recording") can be added for users who
  mount the phone and watch live stats — for example on a bike handlebar. It must be
  explicit and off by default, and it changes only the Record screen's wake flag, nothing
  about how recording works. When the Settings screen lands, this is a natural first
  effective setting to add (and it satisfies the "only settings that something reads" rule).

  **Landed 2026-08-19** with the M1.5 Settings screen, on exactly those terms: off by default,
  and it sets `View.keepScreenOn` on the Record screen only while the state is `Recording`.
  Paused deliberately does not qualify — nothing is being captured to watch — and the flag is
  cleared on dispose so it can never outlive the screen that set it. Recording itself is
  untouched.
- This decision is independent of the wake lock the service itself may hold to keep the CPU
  alive for location delivery; that is a recording-durability mechanism, not a screen
  behaviour, and is unaffected here.
