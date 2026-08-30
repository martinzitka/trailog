package io.github.martinzitka.trailog.ui.settings

import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.data.ActivityRef
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
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Unit tests for [ExportViewModel] — the progress state machine around the bulk export.
 *
 * No device and no database: the worklist and the loader are constructor lambdas and the
 * destination is a [ByteArrayOutputStream], so this is a plain JVM test (CLAUDE.md testing policy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `starts idle`() {
        assertEquals(ExportUiState.Idle, viewModel().uiState.value)
    }

    @Test fun `writes every activity and reports how many`() = runTest(dispatcher) {
        val sink = ByteArrayOutputStream()
        val vm = viewModel(refs = refs("a", "b", "c"))

        vm.exportAll(openSink = { sink }, entryName = ::nameOf)
        advanceUntilIdle()

        assertEquals(ExportUiState.Done(3), vm.uiState.value)
        assertEquals(3, entryNames(sink).size)
    }

    @Test fun `an empty history finishes with nothing exported rather than failing`() =
        runTest(dispatcher) {
            val vm = viewModel(refs = emptyList())

            vm.exportAll(openSink = { ByteArrayOutputStream() }, entryName = ::nameOf)
            advanceUntilIdle()

            assertEquals(ExportUiState.Done(0), vm.uiState.value)
        }

    @Test fun `progress counts up to the size of the worklist`() = runTest(dispatcher) {
        val seen = mutableListOf<ExportUiState>()
        val vm = viewModel(refs = refs("a", "b"))

        vm.exportAll(
            openSink = { RecordingSink(seen, vm) },
            entryName = ::nameOf,
        )
        // The state before anything is counted is Running(0, 0) — the screen's indeterminate
        // moment — and it is set synchronously, so a tap is never a button that does nothing.
        assertEquals(ExportUiState.Running(done = 0, total = 0), vm.uiState.value)
        advanceUntilIdle()

        // Every state observed while bytes were being written knew the size of the worklist, and
        // the count never ran past it — the screen can render "n of 2" without ever lying.
        val running = seen.filterIsInstance<ExportUiState.Running>()
        assertEquals(seen.size, running.size)
        assertTrue(running.all { it.total == 2 && it.done in 0..2 })
        assertEquals(ExportUiState.Done(2), vm.uiState.value)
    }

    @Test fun `a destination that cannot be opened is a failure, not a crash`() =
        runTest(dispatcher) {
            val vm = viewModel(refs = refs("a"))

            vm.exportAll(openSink = { null }, entryName = ::nameOf)
            advanceUntilIdle()

            assertEquals(ExportUiState.Failed, vm.uiState.value)
        }

    @Test fun `a sink that fails mid-write is a failure, not a crash`() = runTest(dispatcher) {
        val vm = viewModel(refs = refs("a"))
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("no space")
        }

        vm.exportAll(openSink = { failing }, entryName = ::nameOf)
        advanceUntilIdle()

        assertEquals(ExportUiState.Failed, vm.uiState.value)
    }

    @Test fun `a second request while one is running is ignored`() = runTest(dispatcher) {
        var opened = 0
        val vm = viewModel(refs = refs("a", "b"))

        vm.exportAll(openSink = { opened++; ByteArrayOutputStream() }, entryName = ::nameOf)
        vm.exportAll(openSink = { opened++; ByteArrayOutputStream() }, entryName = ::nameOf)
        advanceUntilIdle()

        assertEquals(1, opened)
        assertTrue(vm.uiState.value is ExportUiState.Done)
    }

    @Test fun `a finished export can be run again`() = runTest(dispatcher) {
        var opened = 0
        val vm = viewModel(refs = refs("a"))

        vm.exportAll(openSink = { opened++; ByteArrayOutputStream() }, entryName = ::nameOf)
        advanceUntilIdle()
        vm.exportAll(openSink = { opened++; ByteArrayOutputStream() }, entryName = ::nameOf)
        advanceUntilIdle()

        assertEquals(2, opened)
        assertEquals(ExportUiState.Done(1), vm.uiState.value)
    }

    @Test fun `the export is a read - it never deletes or rewrites an activity`() =
        runTest(dispatcher) {
            val loaded = mutableListOf<String>()
            val vm = viewModel(
                refs = refs("a", "b"),
                load = { id -> loaded += id; activity(id) },
            )

            vm.exportAll(openSink = { ByteArrayOutputStream() }, entryName = ::nameOf)
            advanceUntilIdle()

            // The only thing the export is given is a loader. There is no write path to assert
            // against, which is the point: the ViewModel cannot modify an activity even by mistake.
            assertEquals(listOf("a", "b"), loaded)
        }

    // ---- helpers ---------------------------------------------------------------------------

    /** Records the ViewModel's state each time a byte reaches the sink, mid-export. */
    private class RecordingSink(
        private val seen: MutableList<ExportUiState>,
        private val vm: ExportViewModel,
    ) : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            seen += vm.uiState.value
            super.write(b, off, len)
        }
    }

    private fun viewModel(
        refs: List<ActivityRef> = refs("a"),
        load: suspend (String) -> Activity? = { activity(it) },
    ) = ExportViewModel(
        activityRefs = { refs },
        loadActivity = load,
        ioDispatcher = dispatcher,
    )

    private fun refs(vararg ids: String) =
        ids.mapIndexed { i, id -> ActivityRef(id = id, startTime = DAY + i * 1000L) }

    private fun nameOf(slug: String, startTime: Long) = "$slug-$startTime.gpx"

    private fun activity(id: String) = Activity(
        id = UUID.nameUUIDFromBytes(id.toByteArray()),
        type = ActivityType.CYCLING,
        name = "Ride $id",
        points = List(5) { i ->
            RawPoint(
                latitude = 50.0 + i * 0.0001,
                longitude = 14.0 + i * 0.0001,
                altitude = 200.0,
                accuracy = 5.0,
                time = Instant.fromEpochSeconds(i.toLong()),
                segmentIndex = 0,
            )
        },
    )

    private fun entryNames(out: ByteArrayOutputStream): List<String> = buildList {
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name)
            }
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
