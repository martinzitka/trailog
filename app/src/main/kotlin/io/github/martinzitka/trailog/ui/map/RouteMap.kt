package io.github.martinzitka.trailog.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.R
import kotlin.math.cos

/** A single geographic point for the trace. Lat/lon in WGS84 degrees, matching the raw model. */
data class TracePoint(val latitude: Double, val longitude: Double)

/**
 * What the user may do to a map.
 *
 * A sealed model rather than a set of nullable callbacks, because the combinations that make
 * sense are few and the ones that do not are actively broken. In particular a map that pans
 * **and** sits inside a scrolling container must suspend that scroll while a finger is down:
 * otherwise Compose's scroll gesture and MapLibre both chase every single-finger drag, and which
 * one wins varies from gesture to gesture. Making that obligation a field of the variant that
 * enables panning is what stops a caller taking the gestures without it. ADR 0016 records why
 * both pairings are obligations rather than conveniences.
 */
sealed interface MapInteraction {

    /** A picture of a route. No gestures, no taps. */
    data object None : MapInteraction

    /**
     * No gestures; the whole surface is one tap target.
     *
     * This is what an embedded preview wants: a drag over it scrolls the page as a drag over any
     * other part of the page does, and a tap opens the fullscreen map, where panning belongs.
     *
     * @property hostScroll the scroll state of the page the map sits in, or null when it sits in
     *   nothing that scrolls. It is not optional decoration: turning MapLibre's gestures off does
     *   **not** make the view transparent to touch — it still consumes the events it has been told
     *   to ignore, so without this the preview is a dead zone that swallows page scrolls.
     *   (`mapView.isEnabled = false` does not help either; it was tried and reverted.) Forwarding
     *   the drag to the page's own scroll state is what makes the preview behave like the rest of
     *   the page.
     */
    data class Tap(
        val onClick: () -> Unit,
        val hostScroll: ScrollState? = null,
    ) : MapInteraction

    /**
     * Pan and zoom.
     *
     * @property onTouchedChange reports whether a finger is currently on the map. A host inside a
     *   scrolling container **must** disable that scrolling while this is true; a fullscreen host
     *   has nothing to suspend and leaves it at its default.
     * @property onMapClick a tap on the map, reported as the coordinate under the finger. This is
     *   what links a map back to the charts beside it.
     */
    data class Gestures(
        val onTouchedChange: (Boolean) -> Unit = {},
        val onMapClick: ((TracePoint) -> Unit)? = null,
    ) : MapInteraction
}

/**
 * Whether maps draw the style's paths and trails, provided at the app root from the user's
 * preference.
 *
 * An ambient value rather than a parameter on [RouteMap] because it is a display preference that
 * belongs to *every* map equally — the Record screen's, the detail preview's and the fullscreen
 * map's — and threading it through three screens that otherwise have no opinion about it would put
 * the setting in the signature of every one of them. This is the same reasoning that puts the unit
 * system behind `LocalFormatter`.
 *
 * `compositionLocalOf` rather than `staticCompositionLocalOf`: the value is a Boolean compared by
 * equality, so only the maps that read it need to recompose when it changes.
 *
 * The default is true, which matters for tests and previews: a map composed outside the app root
 * draws the style as the style author wrote it.
 */
val LocalShowTrails = compositionLocalOf { true }

/**
 * The map seam. Every screen that shows a route goes through this and nothing else.
 *
 * It picks between two renderers at runtime:
 *
 * - **MapLibre Native** over the self-hosted PMTiles archive, when one is installed.
 * - **A plain polyline** over a blank surface, when one is not.
 *
 * The fallback is not a placeholder or a test seam — it is the state the plan requires of
 * Activity detail: *"Degrades gracefully with no network and no cached tiles — the route still
 * renders over a blank background rather than showing an error."* A fresh install has no region
 * pack, and the route must still be visible. Because the archive lives in app storage rather
 * than the APK, that state is the common one until a pack is downloaded.
 *
 * It follows that JVM tests exercise the fallback, never MapLibre: no archive exists under
 * Robolectric, and the native GL library would not load there anyway. MapLibre rendering can
 * only be verified on a real device (CLAUDE.md, Android specifics).
 *
 * @param segments one polyline per recording segment, in draw order. Rendering each segment
 *   separately is what stops a recording gap being bridged.
 * @param contentDescription accessibility label for the whole map surface.
 * @param showEndMarker whether to mark the most recent point — the live position while recording.
 *   Honoured by both renderers: on an out-and-back the return leg is drawn over the outbound one,
 *   so the line by itself cannot say where the rider currently is.
 * @param marker a position to cross on the route — the chart cursor's counterpart on the map.
 *   Null means no position is selected.
 * @param cameraMode framing behaviour; [MapCameraMode.FOLLOW_END] also enables the re-centre
 *   control. Ignored by the fallback renderer, which always fits the route.
 * @param interaction what the user may do to the map. The fallback renderer cannot pan or zoom
 *   whatever this says — there is nothing underneath to pan over — but it does honour taps.
 */
@Composable
fun RouteMap(
    segments: List<List<TracePoint>>,
    modifier: Modifier = Modifier,
    contentDescription: String,
    showEndMarker: Boolean = true,
    emptyLabel: String? = null,
    marker: TracePoint? = null,
    cameraMode: MapCameraMode = MapCameraMode.FIT_BOUNDS,
    interaction: MapInteraction = MapInteraction.None,
) {
    val gestures = interaction as? MapInteraction.Gestures
    val tap = interaction as? MapInteraction.Tap
    val context = LocalContext.current
    val hasGeometry = segments.any { it.isNotEmpty() }
    val lineColor = MaterialTheme.colorScheme.primary
    val endMarkerColor = MaterialTheme.colorScheme.secondary
    // The cursor has to be findable on a busy map at a glance, and must not be mistaken for the
    // route or for the live-position dot; error is the one role in the scheme guaranteed to
    // contrast with both, in both themes.
    val cursorColor = MaterialTheme.colorScheme.error
    val cursorHaloColor = MaterialTheme.colorScheme.surface

    // The one source Trailog ships (ADR 0015). When a source picker exists this becomes a
    // preference lookup; going through the model now keeps that a substitution rather than a
    // rewrite, and stops the model being unused code.
    val source = BuiltInMapSources.selfHosted

    // Only a bundled style is renderable today. A Remote or RasterTemplate source is a valid
    // value of the model but has no renderer yet, so it degrades to the polyline below rather
    // than crashing on a cast — the model deliberately runs ahead of the implementation.
    val styleAsset = (source.style as? StyleSource.Bundled)?.assetPath

    // Resolved once per composition rather than per frame: this touches the filesystem, and the
    // archive cannot appear or vanish while a screen is open.
    val archive = remember(source) {
        MapTiles.findArchive(context).takeIf { source.worksOffline }
    }

    // Whether the camera is still under the app's control. True until a user gesture moves it,
    // for both camera modes: a re-fit is as unwelcome as a follow once someone has panned away.
    // Survives rotation so a config change does not yank the user back.
    var cameraAttached by rememberSaveable(cameraMode) { mutableStateOf(true) }
    // Bumped to re-issue a camera move even when the target point has not changed.
    var recentreToken by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (archive != null && styleAsset != null) {
            MapLibreRouteMap(
                segments = segments,
                archive = archive,
                gesturesEnabled = gestures != null,
                styleAssetPath = styleAsset,
                // The fallback renderer draws no tiles at all, so there are no trails to hide
                // there and nothing to pass on to it.
                showTrails = LocalShowTrails.current,
                lineColor = lineColor,
                showEndMarker = showEndMarker,
                endMarkerColor = endMarkerColor,
                marker = marker,
                cursorColor = cursorColor,
                cursorHaloColor = cursorHaloColor,
                onMapClick = gestures?.onMapClick,
                // null means "leave the camera where the user put it". Once someone pans away
                // from a following map, neither following nor re-fitting is wanted — both would
                // fight the gesture that just happened.
                cameraMode = cameraMode.takeIf { cameraAttached },
                recentreToken = recentreToken,
                onFollowBrokenByUser = { cameraAttached = false },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (gestures != null) {
                            Modifier.reportTouches(gestures.onTouchedChange)
                        } else {
                            Modifier
                        },
                    ),
            )
            // Offered once a gesture has taken the camera off the route — in either mode.
            // Without it a pan on the fullscreen map would strand the user away from their own
            // track with no way back except leaving the screen.
            if (gestures != null && !cameraAttached) {
                SmallFloatingActionButton(
                    onClick = {
                        cameraAttached = true
                        recentreToken++
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = stringResource(
                            // The same control means different things per mode: follow the live
                            // position, or frame the finished route.
                            if (cameraMode == MapCameraMode.FOLLOW_END) {
                                R.string.record_map_recenter_cd
                            } else {
                                R.string.detail_map_refit_cd
                            },
                        ),
                    )
                }
            }
            TapOverlay(tap)
            return@Box
        }

        if (!hasGeometry) {
            if (emptyLabel != null) {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            // Deliberately no tap target: there is no route and no tiles, so a fullscreen map
            // would open on the same nothing. An empty message that behaves like a button is
            // worse than one that does not.
            return@Box
        }

        FallbackRouteCanvas(
            segments = segments,
            showEndMarker = showEndMarker,
            marker = marker,
            lineColor = lineColor,
            endMarkerColor = endMarkerColor,
            cursorColor = cursorColor,
            onMapClick = gestures?.onMapClick,
        )
        TapOverlay(tap)
    }
}

/**
 * A whole-surface tap target, laid over whichever renderer drew.
 *
 * It sits above the MapView, so MapLibre never sees the gesture — which is correct here:
 * [MapInteraction.Tap] is only used where the map is a preview with its own gestures off.
 */
@Composable
private fun BoxScope.TapOverlay(tap: MapInteraction.Tap?) {
    if (tap == null) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .then(
                if (tap.hostScroll == null) {
                    Modifier
                } else {
                    // Drives the page's scroll state directly, because the MapView underneath
                    // would otherwise eat the drag. reverseDirection because scrollable's delta
                    // sign is the opposite of verticalScroll's.
                    Modifier.scrollable(
                        state = tap.hostScroll,
                        orientation = Orientation.Vertical,
                        reverseDirection = true,
                    )
                },
            )
            .clickable(
                onClickLabel = stringResource(R.string.map_open_fullscreen),
                onClick = tap.onClick,
            ),
    )
}

/**
 * The no-tiles renderer: the route as a plain polyline over a blank surface, fitted to the box.
 *
 * It cannot pan or zoom — there is nothing underneath to pan over — but it does resolve a tap
 * back to a coordinate, so the map-to-chart link behaves the same with and without tiles. That
 * also makes the link testable: this is the renderer Robolectric gets.
 */
@Composable
private fun FallbackRouteCanvas(
    segments: List<List<TracePoint>>,
    showEndMarker: Boolean,
    marker: TracePoint?,
    lineColor: Color,
    endMarkerColor: Color,
    cursorColor: Color,
    onMapClick: ((TracePoint) -> Unit)?,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (onMapClick == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(segments, onMapClick) {
                        detectTapGestures { offset ->
                            // The same fit the draw pass uses, computed from the pointer scope's
                            // own size, so tap and drawing cannot disagree.
                            val projection = Projection.fit(
                                segments,
                                size.width.toFloat(),
                                size.height.toFloat(),
                                PADDING_PX,
                            )
                            onMapClick(projection.unproject(offset.x, offset.y))
                        }
                    }
                },
            ),
    ) {
        val projection = Projection.fit(segments, size.width, size.height, PADDING_PX)
        for (segment in segments) {
            if (segment.size < 2) continue
            val path = Path()
            segment.forEachIndexed { i, point ->
                val (x, y) = projection.project(point)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = STROKE_PX))
        }
        if (showEndMarker) {
            segments.lastOrNull { it.isNotEmpty() }?.last()?.let { end ->
                val (x, y) = projection.project(end)
                drawCircle(color = endMarkerColor, radius = MARKER_PX, center = Offset(x, y))
            }
        }
        if (marker != null) {
            val (x, y) = projection.project(marker)
            drawLine(
                color = cursorColor,
                start = Offset(x - CURSOR_ARM_PX, y),
                end = Offset(x + CURSOR_ARM_PX, y),
                strokeWidth = CURSOR_STROKE_PX,
            )
            drawLine(
                color = cursorColor,
                start = Offset(x, y - CURSOR_ARM_PX),
                end = Offset(x, y + CURSOR_ARM_PX),
                strokeWidth = CURSOR_STROKE_PX,
            )
        }
    }
}

private const val PADDING_PX = 24f
private const val STROKE_PX = 6f
private const val MARKER_PX = 12f
private const val CURSOR_ARM_PX = 18f
private const val CURSOR_STROKE_PX = 4f

/**
 * An equirectangular fit of a set of geographic points into a canvas rectangle, preserving
 * aspect ratio. Longitude is scaled by cos(latitude) so the track is not stretched east–west at
 * Czech latitudes. A degenerate extent (a single point, or all points coincident) centres
 * rather than dividing by zero.
 *
 * Used only by the no-tiles fallback. MapLibre does its own projection.
 */
private class Projection(
    private val lonScale: Double,
    private val minX: Double,
    private val minY: Double,
    private val scale: Double,
    private val offsetX: Float,
    private val offsetY: Float,
    private val height: Float,
) {
    fun project(p: TracePoint): Pair<Float, Float> {
        val px = (p.longitude * lonScale - minX) * scale
        val py = (p.latitude - minY) * scale
        val x = offsetX + px.toFloat()
        // Screen y grows downward; latitude grows up, so invert within the fitted box.
        val y = height - offsetY - py.toFloat()
        return x to y
    }

    /** The inverse of [project]: which coordinate a point on the canvas stands for. */
    fun unproject(x: Float, y: Float): TracePoint {
        val px = (x - offsetX) / scale
        val py = (height - offsetY - y) / scale
        return TracePoint(latitude = py + minY, longitude = (px + minX) / lonScale)
    }

    companion object {
        fun fit(
            segments: List<List<TracePoint>>,
            width: Float,
            height: Float,
            padding: Float,
        ): Projection {
            val points = segments.flatten()
            val midLat = points.sumOf { it.latitude } / points.size
            val lonScale = cos(Math.toRadians(midLat)).coerceAtLeast(1e-6)

            val xs = points.map { it.longitude * lonScale }
            val ys = points.map { it.latitude }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()

            val spanX = (maxX - minX).takeIf { it > 0 } ?: 1.0
            val spanY = (maxY - minY).takeIf { it > 0 } ?: 1.0
            val usableW = (width - 2 * padding).coerceAtLeast(1f)
            val usableH = (height - 2 * padding).coerceAtLeast(1f)
            val scale = minOf(usableW / spanX, usableH / spanY)

            // Centre the fitted track within the padded box.
            val drawnW = (spanX * scale).toFloat()
            val drawnH = (spanY * scale).toFloat()
            val offsetX = padding + (usableW - drawnW) / 2f
            val offsetY = padding + (usableH - drawnH) / 2f

            return Projection(lonScale, minX, minY, scale, offsetX, offsetY, height)
        }
    }
}

/**
 * Reports pointer-down and pointer-up without consuming anything.
 *
 * Events are observed in the [PointerEventPass.Initial] pass, which runs top-down before any
 * ancestor gesture detector decides. Observing there — and consuming nothing — lets the caller
 * disable a surrounding scroll *before* it can claim the drag, while MapLibre still receives
 * every event normally.
 *
 * Consuming instead would be the obvious move and would break the map: the embedded view sits
 * below this modifier in the pointer hierarchy and would stop receiving touches altogether.
 */
private fun Modifier.reportTouches(onTouchedChange: (Boolean) -> Unit): Modifier =
    pointerInput(onTouchedChange) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                onTouchedChange(event.changes.any { it.pressed })
            }
        }
    }
