package io.github.martinzitka.trailog.ui.detail

import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.stats.Statistics
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.ActivityStatsEntity
import io.github.martinzitka.trailog.data.ActivityWithStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [ActivityDetailViewModel] — the state mapping, the on-the-fly statistics
 * fallback, the segment-aware route and profiles, splits, and the edit/delete/export intents.
 *
 * No device and no database: the row flow and the three data lambdas are constructor arguments
 * precisely so this is a plain JVM test (CLAUDE.md testing policy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // ---- states -----------------------------------------------------------------------------

    @Test fun `starts in loading before the first read arrives`() = runTest(dispatcher) {
        val vm = viewModel(rows = MutableStateFlow(null), points = emptyList())
        // No subscriber yet, so the flow has not run: the initial value stands.
        assertTrue(vm.uiState.value is ActivityDetailUiState.Loading)
    }

    @Test fun `a missing activity is Gone, so the screen can navigate back`() =
        runTest(dispatcher) {
            val vm = viewModel(rows = MutableStateFlow(null), points = emptyList())
            collecting(vm) {
                assertTrue(vm.uiState.value is ActivityDetailUiState.Gone)
            }
        }

    @Test fun `deleting the activity moves the screen to Gone`() = runTest(dispatcher) {
        val rows = MutableStateFlow<ActivityWithStats?>(row())
        var deleted: String? = null
        val vm = viewModel(
            rows = rows,
            points = ride(),
            onDelete = { id ->
                deleted = id
                rows.value = null
                true
            },
        )
        collecting(vm) {
            assertTrue(vm.uiState.value is ActivityDetailUiState.Loaded)

            vm.delete()
            testScheduler.advanceUntilIdle()

            assertEquals(ACTIVITY_ID, deleted)
            assertTrue(vm.uiState.value is ActivityDetailUiState.Gone)
        }
    }

    // ---- summary figures --------------------------------------------------------------------

    @Test fun `the cached statistics are used when they exist`() = runTest(dispatcher) {
        val vm = viewModel(
            rows = MutableStateFlow(row(stats = stats(distance = 42_000.0, gain = 617.0))),
            points = ride(),
        )
        collecting(vm) {
            val detail = loaded(vm)
            assertEquals(42_000.0, detail.distance, 0.0)
            assertEquals(617.0, detail.elevationGain, 0.0)
            // Millis at rest, whole seconds at the display edge.
            assertEquals(3_723L, detail.elapsedSeconds)
            assertEquals(3_600L, detail.movingSeconds)
            assertFalse(detail.statsPending)
        }
    }

    @Test fun `statistics are computed on the fly when the cache is missing`() =
        runTest(dispatcher) {
            val points = ride()
            val vm = viewModel(rows = MutableStateFlow(row(stats = null)), points = points)
            collecting(vm) {
                val detail = loaded(vm)
                val expected = Statistics.compute(
                    Activity(UUID.fromString(ACTIVITY_ID), ActivityType.CYCLING, "", points),
                )

                assertTrue("a detail screen of dashes is useless", detail.distance > 0.0)
                assertEquals(expected.distance, detail.distance, 1e-9)
                assertEquals(expected.maxSpeed, detail.maxSpeed, 1e-9)
                assertTrue("the screen must say the cache is still pending", detail.statsPending)
            }
        }

    @Test fun `metadata comes straight off the row`() = runTest(dispatcher) {
        val vm = viewModel(
            rows = MutableStateFlow(
                row(name = "Evening loop", notes = "Muddy", type = ActivityType.HIKING),
            ),
            points = ride(),
        )
        collecting(vm) {
            val detail = loaded(vm)
            assertEquals("Evening loop", detail.name)
            assertEquals("Muddy", detail.notes)
            assertEquals(ActivityType.HIKING, detail.type)
            assertEquals(START_TIME, detail.startTime)
        }
    }

    // ---- route, profiles and segment awareness ----------------------------------------------

    @Test fun `the route keeps one polyline per segment so a gap is never bridged`() =
        runTest(dispatcher) {
            val vm = viewModel(rows = MutableStateFlow(row()), points = rideWithGap())
            collecting(vm) {
                val detail = loaded(vm)

                assertEquals(2, detail.segments.size)
                assertEquals(2, detail.elevationProfile.segments.size)
                assertEquals(2, detail.speedProfile.segments.size)
                // The ~70 km jump between segments is not distance and must not be plotted.
                assertTrue(
                    "profile x-extent ${detail.elevationProfile.maxDistance} must exclude the jump",
                    detail.elevationProfile.maxDistance < 10_000.0,
                )
            }
        }

    @Test fun `the profiles carry the plotted values, not empty series`() = runTest(dispatcher) {
        val vm = viewModel(rows = MutableStateFlow(row()), points = ride())
        collecting(vm) {
            val detail = loaded(vm)
            assertFalse(detail.elevationProfile.isEmpty)
            assertFalse(detail.speedProfile.isEmpty)
            assertNotNull(detail.elevationProfile.maxValue)
            assertNotNull(detail.speedProfile.maxValue)
        }
    }

    @Test fun `an activity whose points are gone renders empty rather than crashing`() =
        runTest(dispatcher) {
            // The metadata row exists but the fixes do not — a half-deleted or half-imported state.
            val vm = viewModel(rows = MutableStateFlow(row(stats = null)), points = emptyList())
            collecting(vm) {
                val detail = loaded(vm)
                assertTrue(detail.segments.isEmpty())
                assertTrue(detail.elevationProfile.isEmpty)
                assertTrue(detail.splits.isEmpty())
                assertEquals(0.0, detail.distance, 0.0)
            }
        }

    // ---- splits -----------------------------------------------------------------------------

    @Test fun `splits are per kilometre and numbered from one`() = runTest(dispatcher) {
        // ~2.5 km of eastward line: two full kilometres and a remainder.
        val vm = viewModel(rows = MutableStateFlow(row()), points = ride(count = 350))
        collecting(vm) {
            val splits = loaded(vm).splits

            assertEquals(3, splits.size)
            assertEquals(listOf(1, 2, 3), splits.map { it.number })
            assertEquals(1_000.0, splits[0].distance, 1.0)
            assertEquals(1_000.0, splits[1].distance, 1.0)
            assertTrue(
                "the final split is a remainder and is shown at its real length",
                splits[2].distance < 1_000.0,
            )
        }
    }

    @Test fun `an imperial split interval re-laps the ride at miles rather than relabelling it`() =
        runTest(dispatcher) {
            // The same ~2.5 km ride: two kilometre laps become one mile lap and a remainder.
            val vm = viewModel(
                rows = MutableStateFlow(row()),
                points = ride(count = 350),
                splitInterval = flowOf(ActivityDetailViewModel.SPLIT_DISTANCE_IMPERIAL),
            )
            collecting(vm) {
                val splits = loaded(vm).splits

                assertEquals("~2.5 km is one full mile plus a remainder", 2, splits.size)
                assertEquals(
                    ActivityDetailViewModel.SPLIT_DISTANCE_IMPERIAL,
                    splits[0].distance,
                    1.0,
                )
                assertFalse("a full mile lap is not partial", splits[0].isPartial)
                assertTrue("the tail is short of a mile", splits[1].isPartial)
            }
        }

    @Test fun `changing the unit preference re-computes the splits while the screen is open`() =
        runTest(dispatcher) {
            val interval = MutableStateFlow(ActivityDetailViewModel.SPLIT_DISTANCE_METRIC)
            val vm = viewModel(
                rows = MutableStateFlow(row()),
                points = ride(count = 350),
                splitInterval = interval,
            )
            collecting(vm) {
                assertEquals(3, loaded(vm).splits.size)

                interval.value = ActivityDetailViewModel.SPLIT_DISTANCE_IMPERIAL
                testScheduler.advanceUntilIdle()

                assertEquals(
                    "switching units must re-lap, not leave a stale kilometre table",
                    2,
                    loaded(vm).splits.size,
                )
            }
        }

    @Test fun `per-split elevation sums to the activity total`() = runTest(dispatcher) {
        val vm = viewModel(
            rows = MutableStateFlow(row(stats = null)),
            points = ride(count = 350, altitudeAt = { 300.0 + it * 0.5 }),
        )
        collecting(vm) {
            val detail = loaded(vm)
            assertEquals(
                detail.elevationGain,
                detail.splits.sumOf { it.elevationGain },
                1e-6,
            )
        }
    }

    // ---- intents ----------------------------------------------------------------------------

    @Test fun `saving edits trims the fields and forwards them once`() = runTest(dispatcher) {
        val saved = mutableListOf<Triple<String, String?, ActivityType>>()
        val vm = viewModel(
            rows = MutableStateFlow(row()),
            points = ride(),
            onSave = { _, name, notes, type -> saved += Triple(name, notes, type) },
        )
        collecting(vm) {
            vm.saveEdits("  Evening loop  ", "  Muddy after the rain ", ActivityType.HIKING)
            testScheduler.advanceUntilIdle()

            assertEquals(1, saved.size)
            assertEquals(
                Triple("Evening loop", "Muddy after the rain", ActivityType.HIKING),
                saved.single(),
            )
        }
    }

    @Test fun `buildGpx writes one trkseg per recording segment`() = runTest(dispatcher) {
        val vm = viewModel(rows = MutableStateFlow(row()), points = rideWithGap())

        val gpx = vm.buildGpx()

        assertNotNull(gpx)
        assertEquals(
            "a recording gap must survive export as two <trkseg> elements",
            2,
            Regex("<trkseg>").findAll(gpx!!).count(),
        )
        assertTrue(gpx.contains("<trk>"))
    }

    @Test fun `buildGpx returns null when the activity has vanished`() = runTest(dispatcher) {
        val vm = ActivityDetailViewModel(
            activityId = ACTIVITY_ID,
            row = MutableStateFlow(null),
            loadActivity = { null },
            saveMetadata = { _, _, _, _ -> },
            deleteActivity = { true },
            computeDispatcher = dispatcher,
        )

        assertNull(vm.buildGpx())
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Subscribes to `uiState` (it is `WhileSubscribed`), settles the scheduler, then runs [body]. */
    private fun TestScope.collecting(vm: ActivityDetailViewModel, body: () -> Unit) {
        val job: Job = launch { vm.uiState.collect { } }
        testScheduler.advanceUntilIdle()
        try {
            body()
        } finally {
            job.cancel()
        }
    }

    private fun loaded(vm: ActivityDetailViewModel) =
        (vm.uiState.value as ActivityDetailUiState.Loaded).detail

    private fun viewModel(
        rows: MutableStateFlow<ActivityWithStats?>,
        points: List<RawPoint>,
        onSave: (String, String, String?, ActivityType) -> Unit = { _, _, _, _ -> },
        onDelete: (String) -> Boolean = { true },
        splitInterval: Flow<Double> = flowOf(ActivityDetailViewModel.SPLIT_DISTANCE_METRIC),
    ) = ActivityDetailViewModel(
        activityId = ACTIVITY_ID,
        row = rows,
        loadActivity = { id ->
            Activity(
                id = UUID.fromString(id),
                type = ActivityType.valueOf(rows.value?.activity?.type ?: "CYCLING"),
                name = rows.value?.activity?.name.orEmpty(),
                points = points,
            )
        },
        saveMetadata = { id, name, notes, type -> onSave(id, name, notes, type) },
        deleteActivity = { id -> onDelete(id) },
        splitInterval = splitInterval,
        computeDispatcher = dispatcher,
    )

    /** A straight eastward line at 50°N, one fix per second, ~7.2 m apart. */
    private fun ride(
        count: Int = 100,
        segment: Int = 0,
        startSecond: Long = 0,
        startLon: Double = 14.0,
        altitudeAt: (Int) -> Double? = { 300.0 + it % 20 },
    ): List<RawPoint> = (0 until count).map { i ->
        RawPoint(
            latitude = 50.0,
            longitude = startLon + i * 0.0001,
            altitude = altitudeAt(i),
            accuracy = 5.0,
            time = Instant.fromEpochSeconds(startSecond + i),
            segmentIndex = segment,
        )
    }

    /** Two segments a degree of longitude (~70 km at 50°N) and ten minutes apart. */
    private fun rideWithGap(): List<RawPoint> =
        ride(count = 50, segment = 0) +
            ride(count = 50, segment = 1, startSecond = 600, startLon = 15.0)

    private fun row(
        name: String = "",
        notes: String? = null,
        type: ActivityType = ActivityType.CYCLING,
        stats: ActivityStatsEntity? = stats(),
    ) = ActivityWithStats(
        activity = ActivityEntity(
            id = ACTIVITY_ID,
            type = type.name,
            name = name,
            notes = notes,
            startTime = START_TIME,
            createdAt = START_TIME,
            updatedAt = START_TIME,
        ),
        stats = stats,
    )

    private fun stats(
        distance: Double = 12_345.0,
        gain: Double = 251.0,
    ) = ActivityStatsEntity(
        activityId = ACTIVITY_ID,
        distance = distance,
        elapsedTime = 3_723_000L,
        movingTime = 3_600_000L,
        averageSpeed = 3.4,
        maxSpeed = 9.1,
        elevationGain = gain,
        elevationLoss = 248.0,
        segmentCount = 1,
        pointCount = 100,
        computedAt = START_TIME,
    )

    private companion object {
        // Real activity ids are UUIDv7 strings; loadActivity parses them via UUID.fromString.
        const val ACTIVITY_ID = "019fe178-0000-7000-8000-000000000001"
        const val START_TIME = 1_700_000_000_000L
    }
}
