# 19. Trails are hidden by toggling a layer group the style declares

Date: 2026-08-30

## Status

Accepted

## Context

The outdoor style fork (PR #5) split paths out of the road layers and gave footways, cycleways,
bridleways, steps and MTB routes their own dashed treatments. On a Czech forest map that is a lot
of extra ink: at z15 in the Beskydy a screen can carry more dashed paths than roads, and a route
line in `colorScheme.primary` has to be picked out from among them.

So the styling needs an off switch. The question is what the switch addresses, and where it lives.

Hiding trails cannot mean "reload a different style". The style is bundled as one templated asset
and MapLibre loads it once per map view; swapping it would throw away the GL context, the camera
and the route source, and would make a settings toggle cost a visible map reload.

## Decision

**The style declares which layers are trails, in `metadata."trailog:trailLayers"`, and the app
switches their `visibility` on the already-loaded style.**

The metadata key predates this change — it was added when the fork was written, for exactly this.
`MapTiles.trailLayerIds` reads it out of the style JSON; `MapLibreRouteMap` applies
`PropertyFactory.visibility` to each named layer in a `LaunchedEffect`. Nothing about which layers
draw trails is written in Kotlin.

That matters because the group is not obvious and will grow. It is thirteen layers today: four
Trailog path layers, the upstream steps pair, the path label layer, and the bridge and tunnel
variants of both — a set nobody would reconstruct correctly by grepping ids, and one that changes
whenever the style is re-forked. `BundledStyleTest` asserts every declared id names a layer that
exists, and `MapTilesTest` asserts the reader gets a non-empty group out of the committed asset, so
the two halves cannot drift apart silently.

**Forest tracks are not in the group.** `trailog-track*` covers OSM `class=track`: forest and
agricultural roads, which are drivable, and which are how most rides here leave a village. Hiding
them under a control labelled "paths and trails" would remove things the rider still navigates by.
Upstream OSM Bright drew them as ordinary minor roads, and after the fork they are still roads —
just honestly-coloured ones.

**The control is a Settings toggle, not an on-map layers button.** Trail visibility is a standing
preference rather than something toggled while reading a map: a rider who wants the clutter gone
wants it gone on every screen, and the same value serves the Record map, the detail preview and the
fullscreen map. An on-map control is the better answer once there is more than one thing to toggle
— contours and hillshading are both planned (ADR 0017) — and a layers sheet built for three
switches is a different design from a button built for one. Building the preference now does not
foreclose it; the sheet would write the same field.

**It reaches the maps as a composition local, `LocalShowTrails`, provided at the app root.** This is
the same shape as `LocalFormatter` and for the same reason: three screens compose a `RouteMap` and
none of them has an opinion about trail styling, so putting it in their signatures would spread a
map preference across screens that only pass it through. `compositionLocalOf` rather than
`staticCompositionLocalOf` because the value is a `Boolean` compared by equality, so a change
invalidates only the maps that read it rather than the whole tree.

## Consequences

- **The setting does nothing visible until z14.** `planetiler-openmaptiles` emits `CLASS_PATH` only
  from zoom 14, and every map in the app opens fitted to a whole route — around z11–12 for a 20 km
  ride. A user who flips the switch while looking at a fitted route sees no change and concludes
  the toggle is broken. The Settings row carries a note saying so; there is no code fix, short of
  rebuilding the archive with `--transportation_z13_paths`.
- Hiding trails also hides `highway-name-path`, the path label layer. That is intended — a hidden
  path with a floating name would be worse than either state — but it means the group is "trails
  and their labels", not just geometry.
- A layer named in the group but missing from the style is skipped rather than reported. The ids
  and the layers come from one file and CI fails if they disagree, so crashing a map over a
  cosmetic setting would be the worse trade.
- The default is on. The style draws trails; the toggle exists to hide them, not to reveal them, so
  an upgrading user's map does not change under them.
- Not verifiable off-device. MapLibre never renders under Robolectric, so the tests prove the
  preference reaches an ambient reader and that the group is read correctly from the asset — the
  layers actually disappearing needs the phone.
