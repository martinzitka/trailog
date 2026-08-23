# 14. The unit preference reaches exactly one class, through a CompositionLocal

Date: 2026-08-19

## Status

Accepted

## Context

CLAUDE.md commits to SI internally with no exceptions, and to conversion happening "only at the
display edge, in a single formatting utility". It also predicts this moment: "A user preference for
imperial units will be added later; it must change nothing but that utility."

M1.5 screen 5 (Settings) is where that preference arrives. The discipline held up to this point —
all 30 formatting call sites were in Composables, none in a ViewModel, a mapper, or a test — so the
question was only *how* the preference should travel from storage to those call sites.

`Format` was an `object` with static functions, which cannot carry per-user state. Three ways
forward were considered:

1. **A mutable field on the object** (`Format.units = IMPERIAL`). Smallest diff, worst design:
   process-wide mutable state, invisible at the call site, order-dependent in tests, and a data race
   waiting to happen.
2. **An extra parameter on every function** (`Format.distance(m, units)`). Explicit and stateless,
   but it puts the burden on 30 call sites to pass the right thing, and a missed one is a silently
   metric readout rather than a compile error. It also pushes a preference lookup into leaf
   Composables that have no other reason to know about settings.
3. **An instance provided through the composition.** `Format` becomes a `Formatter` class holding a
   `UnitSystem`, published as a CompositionLocal.

## Decision

**`Formatter` is a class, and screens read the ambient instance from `LocalFormatter`.** It is
provided once, at the app root, from the stored preference.

- The provider is a `staticCompositionLocalOf`. The formatter changes only when the user changes the
  setting, and is read by a great many leaf Composables, so swapping the subtree on change is
  cheaper than tracking per-reader invalidation.
- Its default is `Formatter(METRIC)`, so a preview, a test, or any Composable rendered outside the
  app root still formats correctly instead of crashing or rendering blank.
- **Rendering has exactly one unit-aware consumer, `Formatter`.** A statistic computed in feet, a
  unit symbol stored in the database, or a ViewModel converting a figure for display is the bug this
  ADR exists to prevent. `:core` never learns the preference exists.

### The one legitimate exception: split length

Device testing of the first cut showed the limit of the rule as originally written. With imperial
selected, every figure converted correctly — and the splits table still read **"Per-kilometre
splits"** with a **km** column beside values in mph and ft. Converting the numbers was not enough,
because an imperial rider does not want kilometre laps relabelled; they want the ride re-lapped at
miles. That is a different computation, not a different rendering.

So a second consumer is admitted, narrowly and deliberately:

- `ActivityDetailViewModel` takes `splitInterval: Flow<Double>` — **a distance in metres**. It never
  sees `UnitSystem`; it is handed a length and laps the ride at it. `:core`'s `Statistics.splits`
  already took the interval as an SI parameter, so it is untouched.
- The translation from preference to distance happens in exactly one place, the ViewModel `Factory`,
  which is already the Android edge.
- The heading and column label follow the interval the splits were computed at, chosen at the
  display edge from `LocalFormatter.current.units`. Picking a *word* for a number is rendering.

The distinction to apply to future settings is therefore: **converting an existing figure belongs in
`Formatter`; changing which figure is computed belongs upstream, expressed in SI.** The second is
allowed, must stay expressed in SI, and must not reach `:core` as a unit.
- Time and file names live on `Formatter` too, even though they do not vary by unit system, so there
  is one call surface at the display edge rather than two.

## Consequences

- Switching units re-renders every figure in the app immediately, with no restart and no screen
  needing to know why. There is a test for exactly this, because it is the whole claim.
- A `Formatter` read from a non-composable lambda has to be hoisted into composable scope first
  (the Activity detail export button does this). That is a small, visible cost and it is preferable
  to the alternative of a global.
- Rounding is Java's `String.format`, i.e. HALF_UP on the binary value. This differs from
  round-half-even, which is what caught out a first draft of the pressure test — 1013.25 hPa renders
  as `1013.3`. Expected values in tests must be computed the way the formatter computes them, not
  assumed.
- Imperial conversions use the exact definitions (1 mile = 1609.344 m, 1 foot = 0.3048 m), so the
  conversion is lossless by construction and no tolerance is needed when checking it.
- The imperial distance threshold mirrors the metric one about the mile rather than the kilometre:
  feet below a mile, miles above. This is a legibility choice, not a convention anyone mandates.
- Nothing stored ever changes, so switching systems back and forth is lossless and safe. The
  Settings screen says so, because a user is entitled to be suspicious of a units toggle.
