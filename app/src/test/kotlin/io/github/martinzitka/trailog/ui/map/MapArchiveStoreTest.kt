package io.github.martinzitka.trailog.ui.map

import io.github.martinzitka.trailog.ui.settings.FakeAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Unit tests for [MapArchiveStore] — installing, listing, choosing and removing region packs.
 *
 * Plain JVM against a temporary directory: the store takes a [File] and an `AppSettings`, so no
 * device, no Robolectric and no `Context` are involved. The files are real, which is the point —
 * the interesting behaviour here is what ends up on disk when a copy is interrupted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapArchiveStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    // ---- listing ---------------------------------------------------------------------------

    @Test fun `no archive installed is a normal state`() {
        val store = store()

        assertTrue(store.archives.value.isEmpty())
        assertNull(store.active.value)
    }

    @Test fun `lists only pmtiles files, largest first`() {
        write("small.pmtiles", 100)
        write("big.pmtiles", 900)
        write("notes.txt", 500)
        write("half.pmtiles.part", 800)

        val store = store()

        assertEquals(listOf("big.pmtiles", "small.pmtiles"), store.archives.value.map { it.name })
    }

    @Test fun `the largest archive is used when the user has not chosen`() {
        write("small.pmtiles", 100)
        write("big.pmtiles", 900)

        assertEquals("big.pmtiles", store().active.value?.name)
    }

    @Test fun `the user's choice beats the size rule`() {
        write("small.pmtiles", 100)
        write("big.pmtiles", 900)
        val store = store()

        store.select("small.pmtiles")

        assertEquals("small.pmtiles", store.active.value?.name)
    }

    @Test fun `choosing an archive that is not installed is ignored`() {
        write("big.pmtiles", 900)
        val store = store()

        store.select("gone.pmtiles")

        // A stale choice must never blank the map.
        assertEquals("big.pmtiles", store.active.value?.name)
    }

    // ---- installing ------------------------------------------------------------------------

    @Test fun `installs an archive and makes it the one in use`() = runTest(dispatcher) {
        val store = store()

        val outcome = store.install("Czechia.pmtiles", declaredSize = null, open = { archive(4_096) })

        assertTrue(outcome is InstallOutcome.Installed)
        assertEquals(listOf("Czechia.pmtiles"), store.archives.value.map { it.name })
        assertEquals("Czechia.pmtiles", store.active.value?.name)
    }

    @Test fun `a file that is not a pmtiles archive is rejected without copying it`() =
        runTest(dispatcher) {
            val store = store()

            val outcome = store.install(
                "holiday.jpg",
                declaredSize = null,
                open = { ByteArrayInputStream(ByteArray(50_000) { 0x42 }) },
            )

            assertEquals(InstallOutcome.NotAnArchive, outcome)
            assertTrue(store.archives.value.isEmpty())
            // Nothing at all was written — not even a partial file.
            assertTrue(temp.root.listFiles().orEmpty().none { it.name.startsWith("holiday") })
        }

    @Test fun `an archive that cannot fit is refused before the copy starts`() =
        runTest(dispatcher) {
            val store = store()

            val outcome = store.install(
                "czechia.pmtiles",
                declaredSize = Long.MAX_VALUE,
                open = { error("must not be opened") },
            )

            assertTrue(outcome is InstallOutcome.NotEnoughSpace)
            assertTrue(store.archives.value.isEmpty())
        }

    @Test fun `a copy that fails part-way leaves nothing behind`() = runTest(dispatcher) {
        val store = store()

        val outcome = runCatching {
            store.install(
                "czechia.pmtiles",
                declaredSize = null,
                open = { failingArchive(afterBytes = 1_024) },
            )
        }

        // Either an outcome or a thrown IOException is acceptable; what matters is the directory.
        assertTrue(outcome.isFailure || outcome.getOrNull() is InstallOutcome.Failed)
        assertTrue(store.archives.value.isEmpty())
        assertTrue(temp.root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun `a partial copy is never visible as an archive`() = runTest(dispatcher) {
        // The fallback picks the largest file, so a half-copied country build would otherwise win
        // that contest and blank the map with a truncated archive.
        write("small.pmtiles", 100)
        val store = store()
        var seenDuringCopy: List<String>? = null

        store.install("big.pmtiles", declaredSize = null, open = { archive(200_000) }) {
            if (seenDuringCopy == null && it > 1_000) {
                seenDuringCopy = store.archives.value.map { archive -> archive.name }
            }
        }

        assertEquals(listOf("small.pmtiles"), seenDuringCopy)
        assertEquals(listOf("big.pmtiles", "small.pmtiles"), store.archives.value.map { it.name })
    }

    @Test fun `importing the same name twice keeps both`() = runTest(dispatcher) {
        val store = store()

        store.install("czechia.pmtiles", declaredSize = null, open = { archive(4_096) })
        store.install("czechia.pmtiles", declaredSize = null, open = { archive(8_192) })

        assertEquals(
            setOf("czechia.pmtiles", "czechia-2.pmtiles"),
            store.archives.value.map { it.name }.toSet(),
        )
    }

    @Test fun `an awkward file name becomes a safe one, keeping what it safely can`() =
        runTest(dispatcher) {
            val store = store()

            store.install(
                "Česko / borders (2026).pmtiles",
                declaredSize = null,
                open = { archive(4_096) },
            )

            // The slash and the spaces go; the diacritics and brackets stay, because this name is
            // what the Map data screen shows the user.
            assertEquals(listOf("Česko-borders-(2026).pmtiles"), store.archives.value.map { it.name })
        }

    @Test fun `a name that already ends in the extension does not gain a second one`() =
        runTest(dispatcher) {
            val store = store()

            store.install("CZECHIA.PMTILES", declaredSize = null, open = { archive(4_096) })

            assertEquals(listOf("CZECHIA.pmtiles"), store.archives.value.map { it.name })
        }

    @Test fun `progress is reported as bytes copied`() = runTest(dispatcher) {
        val store = store()
        val seen = mutableListOf<Long>()

        store.install("czechia.pmtiles", declaredSize = 200_000, open = { archive(200_000) }) {
            seen += it
        }

        assertEquals(200_000L, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
    }

    // ---- removing --------------------------------------------------------------------------

    @Test fun `removing an archive deletes the file and falls back to another`() =
        runTest(dispatcher) {
            write("small.pmtiles", 100)
            write("big.pmtiles", 900)
            val store = store()
            store.select("big.pmtiles")

            assertTrue(store.delete("big.pmtiles"))

            assertEquals(listOf("small.pmtiles"), store.archives.value.map { it.name })
            assertEquals("small.pmtiles", store.active.value?.name)
            assertFalse(File(temp.root, "big.pmtiles").exists())
        }

    @Test fun `removing the last archive leaves nothing in use, which is a normal state`() =
        runTest(dispatcher) {
            write("only.pmtiles", 900)
            val store = store()

            assertTrue(store.delete("only.pmtiles"))

            assertTrue(store.archives.value.isEmpty())
            assertNull(store.active.value)
        }

    @Test fun `removing an archive that is already gone reports it`() = runTest(dispatcher) {
        assertFalse(store().delete("never-there.pmtiles"))
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun store(settings: FakeAppSettings = FakeAppSettings()) = MapArchiveStore(
        tilesDir = temp.root,
        settings = settings,
        // Unconfined so the derived `active` flow settles the moment its inputs change, rather
        // than waiting for a scheduler the assertions would have to advance by hand.
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        ioDispatcher = dispatcher,
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

    /** A valid archive that stops readable after [afterBytes] and then throws. */
    private fun failingArchive(afterBytes: Int): InputStream {
        val good = archive(afterBytes)
        return object : InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("read one byte at a time is never used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= afterBytes) throw IOException("the card was pulled out")
                val read = good.read(b, off, len)
                served += read.coerceAtLeast(0)
                return read
            }
        }
    }
}
