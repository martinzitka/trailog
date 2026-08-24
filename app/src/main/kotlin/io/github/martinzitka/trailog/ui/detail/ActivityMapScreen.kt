package io.github.martinzitka.trailog.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.stats.TrackPosition
import io.github.martinzitka.trailog.ui.chart.DualProfileChart
import io.github.martinzitka.trailog.ui.chart.ProfileTrack
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.format.label
import io.github.martinzitka.trailog.ui.map.MapInteraction
import io.github.martinzitka.trailog.ui.map.RouteMap
import io.github.martinzitka.trailog.ui.map.TracePoint

/**
 * The fullscreen map: the route with pan and zoom, and the elevation and speed profiles in a strip
 * beneath it, linked to each other.
 *
 * This is where map gestures belong. Embedded in the scrolling detail page a pannable map fights
 * the page scroll for every single-finger drag; here there is nothing to fight, so the map gets
 * the whole screen and the detail page's map goes back to being a tappable preview.
 *
 * The two views share one selection, held here as a distance along the ride:
 *
 * - Touching or dragging the chart sets it directly.
 * - Tapping the map snaps to the nearest recorded fix and reports *its* distance.
 *
 * Keeping the selection as a distance rather than as a coordinate is what makes the link
 * single-valued. A track crosses itself — a loop ride passes the same junction twice — so a
 * coordinate does not identify a moment, and a chart cursor placed from one would jump between
 * the two passes. Distance along the ride always does.
 *
 * @param onBack pop back to the detail screen. Also called automatically if the activity is
 *   deleted from under this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityMapScreen(
    viewModel: ActivityDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val loaded = (state as? ActivityDetailUiState.Loaded)?.detail
    val title = loaded?.name?.ifBlank { null } ?: loaded?.type?.label()

    // Metres along the ride, not a coordinate — see the class comment. Saveable so a rotation
    // keeps the point the user selected.
    var cursorDistance by rememberSaveable { mutableStateOf<Double?>(null) }
    // Which curves are drawn. Hoisted to the screen so flipping one does not reset on rotation.
    var showElevation by rememberSaveable { mutableStateOf(true) }
    var showSpeed by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title ?: stringResource(R.string.nav_activity_map),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                is ActivityDetailUiState.Loading -> LoadingMap()

                // Deleted from under us — from the detail screen behind this one, or elsewhere.
                // Leaving is the honest response; there is nothing left to show.
                is ActivityDetailUiState.Gone -> LaunchedEffect(Unit) { onBack() }

                is ActivityDetailUiState.Loaded -> MapAndCharts(
                    detail = s.detail,
                    cursorDistance = cursorDistance,
                    onCursorChange = { cursorDistance = it },
                    showElevation = showElevation,
                    onShowElevationChange = { showElevation = it },
                    showSpeed = showSpeed,
                    onShowSpeedChange = { showSpeed = it },
                )
            }
        }
    }
}

@Composable
private fun LoadingMap() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.detail_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapAndCharts(
    detail: ActivityDetail,
    cursorDistance: Double?,
    onCursorChange: (Double) -> Unit,
    showElevation: Boolean,
    onShowElevationChange: (Boolean) -> Unit,
    showSpeed: Boolean,
    onShowSpeedChange: (Boolean) -> Unit,
) {
    val format = LocalFormatter.current
    val position = cursorDistance?.let { detail.track.atDistance(it) }

    Column(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            segments = detail.segments,
            contentDescription = stringResource(R.string.detail_map_cd),
            showEndMarker = false,
            emptyLabel = stringResource(R.string.detail_map_empty),
            marker = position?.let { TracePoint(it.latitude, it.longitude) },
            interaction = MapInteraction.Gestures(
                onMapClick = { tapped ->
                    val nearest = detail.track.nearestTo(tapped.latitude, tapped.longitude)
                    if (nearest != null && nearest.isWithinTapRange(tapped)) {
                        onCursorChange(nearest.distance)
                    }
                },
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CursorReadout(detail = detail, position = position)

                DualProfileChart(
                    left = ProfileTrack(
                        series = detail.elevationProfile,
                        label = stringResource(R.string.detail_chart_elevation),
                        minLabel = detail.elevationProfile.minValue
                            ?.let { format.elevation(it) }.orEmpty(),
                        maxLabel = detail.elevationProfile.maxValue
                            ?.let { format.elevation(it) }.orEmpty(),
                        visible = showElevation,
                        onVisibleChange = onShowElevationChange,
                    ),
                    right = ProfileTrack(
                        series = detail.speedProfile,
                        label = stringResource(R.string.detail_chart_speed),
                        minLabel = detail.speedProfile.minValue
                            ?.let { format.speed(it) }.orEmpty(),
                        maxLabel = detail.speedProfile.maxValue
                            ?.let { format.speed(it) }.orEmpty(),
                        visible = showSpeed,
                        onVisibleChange = onShowSpeedChange,
                    ),
                    cursorDistance = cursorDistance,
                    onCursorChange = onCursorChange,
                    emptyLabel = stringResource(R.string.map_chart_empty),
                    contentDescription = stringResource(R.string.map_chart_cd),
                )
            }
        }
    }
}

/**
 * What is true at the selected point, or an invitation to select one.
 *
 * The two values come from the plotted series rather than from the raw fix under the cursor, so
 * the figures agree with the curve the user is pointing at — both are smoothed, and a raw
 * altitude beside a smoothed line would read as a bug.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CursorReadout(detail: ActivityDetail, position: TrackPosition?) {
    val format = LocalFormatter.current

    if (position == null) {
        Text(
            stringResource(R.string.map_cursor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CursorFigure(R.string.detail_stat_distance, format.distance(position.distance))
        CursorFigure(R.string.detail_stat_elapsed, format.duration(position.elapsedSeconds))
        detail.elevationProfile.valueAt(position.distance)?.let {
            CursorFigure(R.string.detail_chart_elevation, format.elevation(it))
        }
        detail.speedProfile.valueAt(position.distance)?.let {
            CursorFigure(R.string.detail_chart_speed, format.speed(it))
        }
    }
}

@Composable
private fun CursorFigure(@StringRes labelRes: Int, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Whether a tap is close enough to the track to count as pointing at it.
 *
 * Without a limit every tap on empty countryside would fling the cursor to whichever part of the
 * ride happened to be least far away. The limit is in metres rather than pixels, so it is
 * generous when zoomed out and tight when zoomed in — which is the right way round: a tap on a
 * zoomed-out map is a coarse gesture, and one on a zoomed-in map is a precise one.
 */
private fun TrackPosition.isWithinTapRange(tapped: TracePoint): Boolean =
    Geo.haversine(tapped.latitude, tapped.longitude, latitude, longitude) <= MAP_TAP_TOLERANCE

/** How far from the route a tap may land and still select a point, in metres. */
private const val MAP_TAP_TOLERANCE = 200.0
