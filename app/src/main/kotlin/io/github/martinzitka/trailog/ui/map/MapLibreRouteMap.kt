package io.github.martinzitka.trailog.ui.map

import android.graphics.Bitmap
import android.graphics.Paint
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.io.File

/** How the camera should behave. */
enum class MapCameraMode {
    /** Frame the whole route once. Used when reviewing a finished activity. */
    FIT_BOUNDS,

    /** Keep the newest point centred while recording, until the user pans away. */
    FOLLOW_END,
}

/**
 * The real map: MapLibre Native over the self-hosted PMTiles archive.
 *
 * Only reached when an archive is actually present — [RouteMap] falls back to a plain polyline
 * otherwise. Keeping that decision outside this composable means MapLibre's native library is
 * never loaded on a device with no tiles, and never under Robolectric.
 *
 * @param cameraMode how to frame the map, or null to leave the camera exactly where it is —
 *   which is what a user who has just panned away expects.
 * @param gesturesEnabled whether the user can pan and zoom. False makes the map a static
 *   preview, which is what a map embedded in a scrolling page should be.
 * @param showEndMarker whether to mark the newest fix — the live position while recording.
 * @param endMarkerColor the live position marker's fill.
 * @param marker the selected position, crossed on the route, or null for none.
 * @param onMapClick a tap on the map, reported as the coordinate under the finger.
 * @param onFollowBrokenByUser invoked when a user gesture moves the camera, so the caller can
 *   offer a re-centre control.
 */
@Composable
internal fun MapLibreRouteMap(
    segments: List<List<TracePoint>>,
    archive: File,
    styleAssetPath: String,
    lineColor: Color,
    cameraMode: MapCameraMode?,
    gesturesEnabled: Boolean,
    recentreToken: Int,
    onFollowBrokenByUser: () -> Unit,
    modifier: Modifier = Modifier,
    showEndMarker: Boolean = false,
    endMarkerColor: Color = Color.Blue,
    marker: TracePoint? = null,
    cursorColor: Color = Color.Red,
    cursorHaloColor: Color = Color.White,
    onMapClick: ((TracePoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onFollowBroken by rememberUpdatedState(onFollowBrokenByUser)
    val onClick by rememberUpdatedState(onMapClick)

    // The cursor icon is a bitmap rather than a styled circle so it stays the same size at every
    // zoom and reads as a crosshair on the line rather than a blob covering it.
    val density = LocalDensity.current
    val cursorIcon = remember(cursorColor, cursorHaloColor, density) {
        crossIcon(
            sizePx = with(density) { CURSOR_SIZE_DP.dp.toPx() }.toInt(),
            color = cursorColor.toArgb(),
            haloColor = cursorHaloColor.toArgb(),
        )
    }

    // MapLibre.getInstance loads the native library. Doing it lazily here — rather than in
    // Application.onCreate — keeps the cost off every cold start of a phone that never opens a
    // map, and keeps it out of unit tests entirely.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    // MapView is a plain Android View with a hand-rolled lifecycle that must be driven manually;
    // skipping onStop/onDestroy leaks the GL surface and the location engine.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            if (map == null) {
                view.getMapAsync { libreMap ->
                    map = libreMap
                    // The style is a bundled template; the archive path is only known now.
                    val json = MapTiles.loadStyleJson(
                        context = context,
                        tileUrl = MapTiles.tileUrl(archive),
                        assetPath = styleAssetPath,
                    )
                    libreMap.setStyle(Style.Builder().fromJson(json)) { styleReady = true }

                    // A map the user cannot move needs no gesture handling at all. Turning the
                    // gestures off is what makes it a preview rather than a trap inside a
                    // scrolling page.
                    libreMap.uiSettings.setAllGesturesEnabled(gesturesEnabled)

                    // REASON_API_GESTURE is the user's finger; animations we start ourselves
                    // report a different reason and must not count as breaking follow.
                    libreMap.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            onFollowBroken()
                        }
                    }

                    // Registered once, reading the current callback through rememberUpdatedState:
                    // MapLibre has no remove-and-re-add cycle that is cheap enough to run per
                    // recomposition, and a stale captured lambda would silently stop linking the
                    // map to the charts. Returning false leaves the tap unconsumed.
                    libreMap.addOnMapClickListener { latLng ->
                        onClick?.invoke(TracePoint(latLng.latitude, latLng.longitude))
                        false
                    }
                }
            }
        },
    )

    LaunchedEffect(map, gesturesEnabled) {
        map?.uiSettings?.apply {
            setAllGesturesEnabled(gesturesEnabled)
            // Pan and zoom only. Rotate and tilt are almost always accidental by-products of a
            // two-finger zoom on a route map, and a track read off-north is harder to relate to
            // the ground — plus the compass that appears to undo it is itself a puzzle.
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
        }
    }

    // Route geometry. Rebuilt whenever the trace grows, which while recording is every fix.
    LaunchedEffect(map, styleReady, segments, lineColor) {
        val libreMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        libreMap.getStyle { style -> style.setRoute(segments, lineColor) }
    }

    // The live position, while recording.
    //
    // The drawn track alone cannot say where the rider is now, and on an out-and-back it is
    // actively misleading: the return leg lies on top of the outbound one, so the line looks
    // identical whether you are two minutes or two hours in. A marker on the newest fix is the
    // only thing that answers "where am I on this".
    LaunchedEffect(map, styleReady, showEndMarker, segments.lastPointOrNull(), endMarkerColor) {
        val libreMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        libreMap.getStyle { style ->
            style.setPosition(
                geometry = endMarkerGeometry(segments, showEndMarker),
                color = endMarkerColor,
                haloColor = cursorHaloColor,
            )
        }
    }

    // The cursor icon, re-uploaded whenever it changes — which it does when the user switches
    // theme, since the app swaps palettes in place rather than recreating the activity. Kept
    // apart from the marker effect so moving the cursor does not re-upload a bitmap, and declared
    // first so the symbol layer below never references an image that is not there yet.
    LaunchedEffect(map, styleReady, cursorIcon) {
        val libreMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        libreMap.getStyle { style -> style.addImage(CURSOR_IMAGE, cursorIcon) }
    }

    // The cursor: the chart's selected position, crossed on the route.
    LaunchedEffect(map, styleReady, marker) {
        val libreMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        libreMap.getStyle { style -> style.setCursor(marker) }

        // Keep the cursor on screen, but only when it has actually left it. Panning on every
        // selection would fight a user who has deliberately zoomed into a climb; doing nothing at
        // all would let a chart tap mark a place they cannot see. Zoom is preserved either way,
        // and this move is not a gesture, so it does not detach the camera.
        val target = marker ?: return@LaunchedEffect
        if (!mapView.awaitLayout()) return@LaunchedEffect
        val latLng = LatLng(target.latitude, target.longitude)
        if (!libreMap.projection.visibleRegion.latLngBounds.contains(latLng)) {
            libreMap.easeCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    // Camera. FIT_BOUNDS runs once per route; FOLLOW_END tracks the newest point.
    LaunchedEffect(map, styleReady, cameraMode, segments.lastPointOrNull(), recentreToken) {
        val libreMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val end = segments.lastPointOrNull()
        when {
            cameraMode == null -> Unit
            // No geometry yet — the Record screen before the first fix arrives. Without this the
            // camera sits at MapLibre's default null-island position and the user is shown the
            // whole globe centred on the Atlantic, which reads as a broken map.
            end == null -> libreMap.fitCoverage(mapView, archive)
            cameraMode == MapCameraMode.FIT_BOUNDS -> libreMap.fitRoute(mapView, segments)
            else -> libreMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(end.latitude, end.longitude),
                    FOLLOW_ZOOM,
                ),
            )
        }
    }
}

private const val ROUTE_SOURCE = "trailog-route"
private const val ROUTE_LAYER = "trailog-route-line"
private const val ROUTE_WIDTH_DP = 5f
private const val FOLLOW_ZOOM = 15.0
private const val FIT_PADDING_PX = 64

private const val POSITION_SOURCE = "trailog-position"
private const val POSITION_LAYER = "trailog-position-circle"
private const val POSITION_RADIUS_DP = 7f
private const val POSITION_STROKE_DP = 3f

private const val CURSOR_SOURCE = "trailog-cursor"
private const val CURSOR_LAYER = "trailog-cursor-symbol"
private const val CURSOR_IMAGE = "trailog-cursor-image"
private const val CURSOR_SIZE_DP = 28f

/**
 * Builds the route geometry: one `LineString` per recording segment inside one
 * `MultiLineString`.
 *
 * Extracted from rendering so it can be tested without a GL context. This is the single point
 * where "a gap is a gap" is decided, and a unit test is the only honest way to assert it — a
 * screenshot cannot distinguish a correctly-drawn 20 m break from a bridged one, and real
 * tracks rarely contain a gap large enough to see.
 *
 * Segments of fewer than two points are dropped: a one-point segment has no line to draw, and
 * including it would make MapLibre join it to whatever came next.
 */
internal fun routeGeometry(segments: List<List<TracePoint>>): MultiLineString =
    MultiLineString.fromLineStrings(
        segments
            .filter { it.size >= 2 }
            .map { segment ->
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) })
            },
    )

/**
 * Installs (or updates) the route line.
 *
 * Each recording segment becomes its own LineString inside one MultiLineString. That is what
 * keeps a gap a gap: MapLibre draws nothing between the end of one LineString and the start of
 * the next, so a signal blackout or a process kill never becomes a straight line across the map
 * with kilometres of phantom distance (CLAUDE.md: never interpolate across a segment boundary).
 */
private fun Style.setRoute(segments: List<List<TracePoint>>, color: Color) {
    val geometry = routeGeometry(segments)

    val existing = getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
    if (existing != null) {
        existing.setGeoJson(geometry)
        // Re-applied rather than set once: the app swaps palettes in place on a theme change, so
        // a layer coloured only at creation would keep the old scheme's line until the screen is
        // left and re-entered.
        getLayerAs<LineLayer>(ROUTE_LAYER)?.setProperties(PropertyFactory.lineColor(color.toArgb()))
        return
    }
    addSource(GeoJsonSource(ROUTE_SOURCE, geometry))
    addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(color.toArgb()),
            PropertyFactory.lineWidth(ROUTE_WIDTH_DP),
            // Rounded ends and joins stop a dense track looking like a chain of dashes.
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/**
 * The live position as GeoJSON: the newest fix across every segment, or nothing.
 *
 * Extracted from rendering so it can be tested without a GL context — the same reason
 * [routeGeometry] is. Returning an empty collection rather than skipping the update is what
 * *removes* the marker when recording stops, instead of leaving it stranded at the last fix.
 */
internal fun endMarkerGeometry(
    segments: List<List<TracePoint>>,
    show: Boolean,
): FeatureCollection {
    val end = segments.lastPointOrNull().takeIf { show }
        ?: return FeatureCollection.fromFeatures(emptyList())
    return FeatureCollection.fromFeature(
        Feature.fromGeometry(Point.fromLngLat(end.longitude, end.latitude)),
    )
}

/**
 * Installs (or moves) the live position marker.
 *
 * A [CircleLayer] rather than a symbol: its radius is in screen pixels, so the marker stays the
 * same size at every zoom without a bitmap to generate, and the contrasting ring keeps it legible
 * where it sits on top of the route line it is meant to be distinguished from.
 */
private fun Style.setPosition(geometry: FeatureCollection, color: Color, haloColor: Color) {
    val paint = arrayOf(
        PropertyFactory.circleRadius(POSITION_RADIUS_DP),
        PropertyFactory.circleColor(color.toArgb()),
        PropertyFactory.circleStrokeWidth(POSITION_STROKE_DP),
        PropertyFactory.circleStrokeColor(haloColor.toArgb()),
    )

    val existing = getSourceAs<GeoJsonSource>(POSITION_SOURCE)
    if (existing != null) {
        existing.setGeoJson(geometry)
        getLayerAs<CircleLayer>(POSITION_LAYER)?.setProperties(*paint)
        return
    }
    addSource(GeoJsonSource(POSITION_SOURCE, geometry))
    addLayer(CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(*paint))
}

/**
 * Installs (or moves) the cursor cross.
 *
 * A symbol rather than a circle or a two-line geometry: a symbol keeps a constant size on screen
 * at every zoom, where a geometric cross drawn in degrees would grow into a landmark as the user
 * zoomed out. Overlap and placement checks are off, because a marker the user placed themselves
 * must never be dropped for colliding with a place label.
 *
 * The icon itself is uploaded separately, by the caller, so that moving the cursor costs a
 * GeoJSON update rather than a bitmap upload.
 */
private fun Style.setCursor(marker: TracePoint?) {
    val geometry = if (marker == null) {
        FeatureCollection.fromFeatures(emptyList())
    } else {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)),
        )
    }

    val existing = getSourceAs<GeoJsonSource>(CURSOR_SOURCE)
    if (existing != null) {
        existing.setGeoJson(geometry)
        return
    }
    addSource(GeoJsonSource(CURSOR_SOURCE, geometry))
    addLayer(
        SymbolLayer(CURSOR_LAYER, CURSOR_SOURCE).withProperties(
            PropertyFactory.iconImage(CURSOR_IMAGE),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
}

/**
 * Draws the cursor: a cross in [color] over a wider stroke of [haloColor], so it stays visible
 * on a light map, a dark map, and on top of the route line itself.
 */
private fun crossIcon(sizePx: Int, color: Int, haloColor: Int): Bitmap {
    val size = sizePx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val centre = size / 2f
    val arm = size * CROSS_ARM_FRACTION
    val stroke = size * CROSS_STROKE_FRACTION

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // Halo first, then the cross on top of it.
    for ((paintColor, width) in listOf(haloColor to stroke * 2f, color to stroke)) {
        paint.color = paintColor
        paint.strokeWidth = width
        canvas.drawLine(centre - arm, centre, centre + arm, centre, paint)
        canvas.drawLine(centre, centre - arm, centre, centre + arm, paint)
    }
    return bitmap
}

private const val CROSS_ARM_FRACTION = 0.34f
private const val CROSS_STROKE_FRACTION = 0.08f

/**
 * Frames the area the installed archive covers, so a map with no route still opens somewhere
 * meaningful — over the user's own downloaded region, whichever one they built.
 */
private suspend fun MapLibreMap.fitCoverage(view: MapView, archive: File) {
    val coverage = MapTiles.coverage(archive) ?: return
    if (!view.awaitLayout()) return
    val bounds = LatLngBounds.Builder()
        .include(LatLng(coverage.minLatitude, coverage.minLongitude))
        .include(LatLng(coverage.maxLatitude, coverage.maxLongitude))
        .build()
    moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX))
}

/** Frames the whole route, or does nothing when there is not enough geometry to frame. */
private suspend fun MapLibreMap.fitRoute(view: MapView, segments: List<List<TracePoint>>) {
    val points = segments.flatten()
    if (points.size < 2) return

    // newLatLngBounds divides by the viewport, so it throws if the view has not been measured.
    //
    // Waiting rather than returning is defensive, not a fix for an observed failure: on the
    // test device the view was always already laid out by the time the style finished loading.
    // But nothing re-triggers this effect afterwards, so if that race ever were lost the camera
    // would sit at MapLibre's default null-island position forever — no tiles, route off-screen,
    // and no error. Waiting costs nothing when layout has already happened.
    if (!view.awaitLayout()) return

    val bounds = LatLngBounds.Builder()
        .includes(points.map { LatLng(it.latitude, it.longitude) })
        .build()
    moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX))
}

/**
 * Suspends until the view has a non-zero size, returning false if it never gets one.
 *
 * Returns immediately when the view is already laid out, which is the common case.
 */
private suspend fun View.awaitLayout(): Boolean {
    if (width > 0 && height > 0) return true
    return withTimeoutOrNull(LAYOUT_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, or_: Int, ob: Int,
                ) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            addOnLayoutChangeListener(listener)
            continuation.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
        }
    } != null
}

private const val LAYOUT_TIMEOUT = 5_000L

/** The newest fix across all segments, which is the live position while recording. */
private fun List<List<TracePoint>>.lastPointOrNull(): TracePoint? =
    lastOrNull { it.isNotEmpty() }?.last()
