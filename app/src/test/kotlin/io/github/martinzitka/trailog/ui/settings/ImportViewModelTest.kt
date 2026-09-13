package io.github.martinzitka.trailog.ui.settings

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.importer.ImportedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests for [ImportViewModel] — the progress state machine around the bulk import.
 *
 * No device and no database: the archive is a byte array and the store is two constructor lambdas,
 * so this is a plain JVM test (CLAUDE.md testing policy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `starts idle`() {
        assertEquals(ImportUiState.Idle, viewModel().uiState.value)
    }

    @Test fun `a finished import reports what it added`() = runTest(dispatcher) {
        val written = mutableListOf<ImportedActivity>()
        val vm = viewModel(write = { written += it })

        vm.importArchive { source(archive("a" to 1, "b" to 2)) }
        advanceUntilIdle()

        val state = vm.uiState.value as ImportUiState.Done
        assertEquals(2, state.report.imported)
        assertEquals(0, state.report.skipped)
        assertEquals(2, written.size)
    }

    @Test fun `activities already present are skipped, not written again`() = runTest(dispatcher) {
        // Re-running an import is the expected case, not an error. The screen has to be able to
        // say "already here" rather than reporting zero additions as though nothing worked.
        val written = mutableListOf<ImportedActivity>()
        val vm = viewModel(exists = { true }, write = { written += it })

        vm.importArchive { source(archive("a" to 1, "b" to 2)) }
        advanceUntilIdle()

        val state = vm.uiState.value as ImportUiState.Done
        assertEquals(0, state.report.imported)
        assertEquals(2, state.report.skipped)
        assertTrue("nothing may be written for an id already stored", written.isEmpty())
    }

    @Test fun `progress is reported while it runs`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.importArchive { source(archive("a" to 1)) }

        assertEquals(ImportUiState.Running(done = 0), vm.uiState.value)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is ImportUiState.Done)
    }

    @Test fun `an archive that cannot be opened fails without throwing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.importArchive { null }
        advanceUntilIdle()

        assertEquals(ImportUiState.Failed, vm.uiState.value)
    }

    @Test fun `a stream that blows up mid-read fails without throwing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.importArchive {
            object : InputStream() {
                override fun read(): Int = throw IOException("storage went away")
            }
        }
        advanceUntilIdle()

        assertEquals(ImportUiState.Failed, vm.uiState.value)
    }

    @Test fun `an empty archive finishes rather than failing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.importArchive { source(archive()) }
        advanceUntilIdle()

        val state = vm.uiState.value as ImportUiState.Done
        assertEquals(0, state.report.total)
    }

    @Test fun `a second request while running is ignored`() = runTest(dispatcher) {
        // Two concurrent passes would race on the same de-duplication check and could write the
        // same activity twice.
        var opened = 0
        val vm = viewModel()

        vm.importArchive { opened++; source(archive("a" to 1)) }
        vm.importArchive { opened++; source(archive("b" to 2)) }
        advanceUntilIdle()

        assertEquals(1, opened)
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun viewModel(
        exists: suspend (UUID) -> Boolean = { false },
        write: suspend (ImportedActivity) -> Unit = {},
    ) = ImportViewModel(exists = exists, write = write, ioDispatcher = dispatcher)

    private fun source(bytes: ByteArray): InputStream = ByteArrayInputStream(bytes)

    private fun archive(vararg entries: Pair<String, Long>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, second) in entries) {
                zip.putNextEntry(ZipEntry("$name.gpx"))
                zip.write(gpx(second).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gpx(second: Long) = Gpx.write(
        GpxTrack(
            name = "Ride",
            description = null,
            type = "cycling",
            points = List(2) { i ->
                RawPoint(
                    latitude = 50.0,
                    longitude = 14.0 + i * 0.001,
                    altitude = null,
                    accuracy = null,
                    time = Instant.fromEpochSeconds(second * 100 + i),
                )
            },
            activityId = UUID.nameUUIDFromBytes(second.toString().toByteArray()),
        ),
    )
}
