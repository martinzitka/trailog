# 16. The fullscreen map, and how a chart and a map point at the same moment

Date: 2026-08-24

## Status

Accepted

## Context

IMPLEMENTATION_PLAN.md's M1.5 screen 6 asks for linked charts: touching a chart marks the
position on the map, selecting a position on the map marks the point on both charts, and the whole
thing stays responsive on a track of ~15,000 points. It does not say where any of this lives.

Two problems arrived together.

**The detail page's map could not be both pannable and embedded.** M1.6 made the map real, and a
pannable MapLibre `MapView` inside a Compose `verticalScroll` fights it for every single-finger
drag — Compose's scroll claims the gesture in its own pointer system before MapLibre's
`requestDisallowInterceptTouchEvent` can matter. The interim fix suspended the page scroll while a
finger was on the map, which worked but meant a drag over 240dp of the page scrolled nothing. That
compromise was recorded at the time as wanting tap-to-fullscreen instead.

**A linked cursor needs somewhere to live that has room for both halves.** A chart small enough to
sit under a 240dp preview, in a page that also scrolls, is not a chart anyone can drag a cursor
along.

## Decision

### The fullscreen map is its own destination

Route `activity/{activityId}/map`, pushed on top of Activity detail, with the bottom navigation bar
hidden for it. Not a mode of the detail screen: the system back button then does the obvious thing,
the bar can be hidden for this one destination without conditional layout elsewhere, and the state
that only matters here — cursor position, series visibility — is scoped to a back-stack entry that
disappears when the user leaves.

The detail page's map becomes a preview again: no gestures, a tap opens the fullscreen map. Panning
now happens only where nothing competes for the gesture.

### The selection is a distance along the ride, not a coordinate

This is the load-bearing decision. A track crosses itself — every out-and-back and every loop
passes the same junction twice — so a coordinate does not identify a moment of the ride. A cursor
placed from a coordinate would jump between the two passes depending on which fix happened to be
nearer, and the chart, whose x-axis is distance, could not place it at all.

Distance always identifies a moment, so it is what the screen holds. The map tap is converted at
the boundary: nearest recorded fix, then *its* distance.

### `TrackIndex` answers both directions from one structure

`:core` gains `TrackIndex`: distance → position by binary search, coordinate → position by linear
scan over haversine distances. Both directions come from one structure built once per activity, so
they cannot disagree about where a segment boundary falls.

It follows the same rules as every other derived view here. Distances accumulate **within segments
only**, exactly as `Statistics.distance` and `TrackProfile` accumulate them, so a recording gap
advances elapsed time but not distance. And a position is always a **real recorded fix**, never an
interpolation between two of them: interpolating would have to decide what to do inside a gap,
where the only true answer is that nothing was recorded.

No spatial index. A track is not a map: one scan of ~14,400 fixes is a fraction of a millisecond,
and a quadtree would be a structure to build, invalidate and get wrong for no measurable gain.

`ProfileSeries.valueAt` reads the plotted value nearest a distance, so the cursor readout reports
what is *drawn*. The series are smoothed and downsampled; re-deriving the figure from raw points
would print a number that disagrees with the curve the user is pointing at.

### One chart with two axes, not two charts

Elevation on the left axis, speed on the right, sharing the distance axis, each with a visibility
toggle. Elevation and speed are read against each other — what the climb cost, where the descent
was quick — and on two stacked charts that comparison is made by eye across a gap. Each series
keeps its own y-scale, because metres and metres per second have no common zero.

Each segment is still drawn as its own `Path`, so a recording gap is a break in the line and can
never become a straight run joining the ends.

### The camera moves only when the cursor leaves the viewport

Panning on every selection fights a user who has deliberately zoomed into a climb. Never panning
lets a chart tap mark a place they cannot see. So: move only when the marker is outside the visible
region, preserving zoom, and not as a gesture — so it does not detach the camera from its mode.

### `MapInteraction` makes the invalid combinations unrepresentable

`RouteMap` takes one sealed parameter rather than several nullable callbacks:

```kotlin
sealed interface MapInteraction {
    data object None
    data class Tap(val onClick: () -> Unit, val hostScroll: ScrollState? = null)
    data class Gestures(
        val onTouchedChange: (Boolean) -> Unit = {},
        val onMapClick: ((TracePoint) -> Unit)? = null,
    )
}
```

Both pairings are obligations, not conveniences. `Gestures` carries `onTouchedChange` because a
pannable map inside a scrolling container **must** suspend that scroll. `Tap` carries `hostScroll`
because switching MapLibre's gestures off does not make the view transparent to touch — it still
consumes the events it was told to ignore, so a preview without this is a band of the page that
refuses to scroll. (`mapView.isEnabled = false` was tried and does not help.) The tap overlay
therefore drives the host's scroll state directly, and `clickable` yields to `scrollable` past
touch slop, so a tap opens the map and a drag scrolls the page.

## Consequences

- A second `ActivityDetailViewModel` instance exists while the fullscreen map is open, scoped to
  its own back-stack entry. It recomputes the profiles and the index for an activity the screen
  underneath has already computed. Accepted: the work is off the main thread and bounded by the
  ride, and sharing one instance would mean scoping a ViewModel to a parent nav graph entry to save
  a few tens of milliseconds.
- `TrackIndex` is built for every Activity detail load, including the many that never open the
  fullscreen map. It holds references plus one `DoubleArray`, so the cost is an array of ~14,400
  doubles rather than a copy of the track.
- A map tap outside 200 m of the route selects nothing. The tolerance is in metres, not pixels, so
  it is forgiving when zoomed out and precise when zoomed in — which is the right way round, but it
  does mean a deliberate tap on a distant part of a zoomed-in ride does nothing rather than jumping
  there.
- Nothing here is provable by the test suite beyond the fallback renderer. Robolectric has no
  PMTiles archive and would not load MapLibre's native library, so the cursor cross, the pan and
  zoom under the tap overlay, and the camera nudge can only be judged on a device — the same
  constraint ADR 0015 records.
- The live position marker had to be fixed to make the Record screen honest about the same
  ambiguity: `showEndMarker` was honoured only by the fallback renderer, so with tiles installed
  the live map drew the track and nothing else. On an out-and-back the return leg lies over the
  outbound one, and the line alone cannot say where the rider is.
