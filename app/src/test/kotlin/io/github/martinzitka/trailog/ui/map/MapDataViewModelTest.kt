package io.github.martinzitka.trailog.ui.map

import io.github.martinzitka.trailog.ui.settings.FakeAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Unit tests for [MapDataViewModel] — what the Map data screen renders while region packs are
 * listed, imported and removed.
 *
 * The store underneath is the real one over a temp directory, because the parts worth testing here
 * are the ones that involve real files: which archive is marked in use, and what the screen shows
 * while a copy is running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapDataViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `starts empty and not yet loaded, so the screen can show a spinner`() {
        val vm = viewModel()

        assertTrue(vm.uiState.value.archives.isEmpty())
        assertFalse(vm.uiState.value.loaded)
    }

    @Test fun `lists installed packs with the one in use marked`() = runTest(dispatcher) {
        write("small.pmtiles", 100)
        write("big.pmtiles", 900)
        val vm = viewModel()

        collecting(vm) {
            val rows = vm.uiState.value.archives
            assertEquals(listOf("big.pmtiles", "small.pmtiles"), rows.map { it.name })
            assertEquals(listOf(true, false), rows.map { it.inUse })
            assertEquals(900L, rows.first().sizeBytes)
        }
    }

    @Test fun `choosing a pack moves the marker`() = runTest(dispatcher) {
        write("small.pmtiles", 100)
        write("big.pmtiles", 900)
        val vm = viewModel()

        collecting(vm) {
            vm.select("small.pmtiles")
            advanceUntilIdle()

            assertEquals(
                "small.pmtiles",
                vm.uiState.value.archives.single { it.inUse }.name,
            )
        }
    }

    @Test fun `importing reports progress and then lists the pack`() = runTest(dispatcher) {
        val vm = viewModel()

        collecting(vm) {
            vm.import("czechia.pmtiles", declaredSize = 200_000, open = { archive(200_000) })
            advanceUntilIdle()

            assertEquals(ImportState.Idle, vm.uiState.value.import)
            assertEquals(listOf("czechia.pmtiles"), vm.uiState.value.archives.map { it.name })
            assertTrue(vm.uiState.value.archives.single().inUse)
        }
    }

    @Test fun `the wrong file is reported without copying anything`() = runTest(dispatcher) {
        val vm = viewModel()

        collecting(vm) {
            vm.import(
                "holiday.jpg",
                declaredSize = 4_000,
                open = { ByteArrayInputStream(ByteArray(4_000)) },
            )
            advanceUntilIdle()

            assertEquals(ImportState.NotAnArchive, vm.uiState.value.import)
            assertTrue(vm.uiState.value.archives.isEmpty())
        }
    }

    @Test fun `a pack that does not fit says so, with both figures`() = runTest(dispatcher) {
        val vm = viewModel()

        collecting(vm) {
            vm.import("czechia.pmtiles", declaredSize = Long.MAX_VALUE, open = { archive(4_096) })
            advanceUntilIdle()

            val state = vm.uiState.value.import
            assertTrue(state is ImportState.NotEnoughSpace)
            assertEquals(Long.MAX_VALUE, (state as ImportState.NotEnoughSpace).required)
        }
    }

    @Test fun `a failure can be dismissed`() = runTest(dispatcher) {
        val vm = viewModel()

        collecting(vm) {
            vm.import("holiday.jpg", declaredSize = null, open = { ByteArrayInputStream(ByteArray(400)) })
            advanceUntilIdle()
            assertEquals(ImportState.NotAnArchive, vm.uiState.value.import)

            vm.dismissProblem()
            advanceUntilIdle()
            assertEquals(ImportState.Idle, vm.uiState.value.import)
        }
    }

    @Test fun `a second import while one runs is ignored`() = runTest(dispatcher) {
        var opened = 0
        val vm = viewModel()

        collecting(vm) {
            vm.import("a.pmtiles", declaredSize = null, open = { opened++; archive(200_000) })
            vm.import("b.pmtiles", declaredSize = null, open = { opened++; archive(200_000) })
            advanceUntilIdle()

            assertEquals(1, opened)
        }
    }

    @Test fun `removing a pack takes it off the list`() = runTest(dispatcher) {
        write("only.pmtiles", 900)
        val vm = viewModel()

        collecting(vm) {
            vm.delete("only.pmtiles")
            advanceUntilIdle()

            assertTrue(vm.uiState.value.archives.isEmpty())
            assertFalse(File(temp.root, "only.pmtiles").exists())
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Runs [body] with a live subscriber on the state, because the flow is `WhileSubscribed` — with
     * nobody collecting, the initial value stands and every assertion here would be about nothing.
     */
    private fun TestScope.collecting(vm: MapDataViewModel, body: () -> Unit) {
        val job: Job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        body()
        job.cancel()
    }

    private fun viewModel() = MapDataViewModel(
        MapArchiveStore(
            tilesDir = temp.root,
            settings = FakeAppSettings(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            ioDispatcher = dispatcher,
        ),
    )

    private fun write(name: String, sizeBytes: Int): File =
        File(temp.root, name).apply { writeBytes(ByteArray(sizeBytes)) }

    /** A stream that opens with a valid PMTiles v3 header and then runs on for [totalBytes]. */
    private fun archive(totalBytes: Int): InputStream {
        val bytes = ByteArray(totalBytes)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[7] = 3
        return ByteArrayInputStream(bytes)
    }
}
