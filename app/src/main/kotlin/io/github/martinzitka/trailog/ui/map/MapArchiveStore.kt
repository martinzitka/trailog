package io.github.martinzitka.trailog.ui.map

import android.content.Context
import io.github.martinzitka.trailog.ui.settings.AppPreferences
import io.github.martinzitka.trailog.ui.settings.AppSettings
import io.github.martinzitka.trailog.ui.settings.PrefsAppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A region pack installed on the device. */
data class InstalledArchive(
    val file: File,
    val sizeBytes: Long,
    /** Null when the header is unreadable — the archive still renders, just with no known extent. */
    val coverage: MapTiles.Coverage?,
) {
    val name: String get() = file.name
}

/** What became of an attempt to install a region pack. */
sealed interface InstallOutcome {

    data class Installed(val archive: InstalledArchive) : InstallOutcome

    /**
     * The chosen file is not a PMTiles archive. Decided from its first 127 bytes, so nothing was
     * copied — picking the wrong file costs no time and no storage.
     */
    data object NotAnArchive : InstallOutcome

    /** Not enough room. Both figures are bytes, so the screen can say how short it is. */
    data class NotEnoughSpace(val required: Long, val available: Long) : InstallOutcome

    /** The copy failed part-way. The partial file has been removed. */
    data object Failed : InstallOutcome
}

/**
 * Which region packs are installed, which one the maps use, and how a new one gets there.
 *
 * Before this existed an archive reached the phone only by `adb push`, and the app never said
 * whether one had arrived: a map with no tiles looked exactly like a map that had failed. The
 * store is what Settings' Map data screen reads and writes, and what [RouteMap] resolves its
 * archive through, so an import is visible on every map without restarting the app.
 *
 * A process-wide singleton for the same reason [PrefsAppSettings] is one: the maps read it while
 * the Map data screen writes it, and two instances would disagree about what is installed.
 *
 * **The active archive is the user's choice, falling back to the largest.** The fallback is what
 * makes an adb-pushed file work with no preference stored at all, and "largest" is the best guess
 * available — a country build dwarfs a city one. Once the user picks, their choice stands until
 * that file goes away.
 *
 * Nothing here logs a coordinate. Sizes and file names only.
 */
class MapArchiveStore(
    private val tilesDir: File,
    private val settings: AppSettings,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val installed = MutableStateFlow(scan())

    /** Every installed archive, largest first. */
    val archives: StateFlow<List<InstalledArchive>> = installed.asStateFlow()

    /**
     * The archive the maps render, or null when none is installed — an ordinary state, not an
     * error: a fresh install has no region pack and the map must still draw the route.
     */
    val active: StateFlow<InstalledArchive?> =
        combine(installed, settings.preferences) { list, prefs -> activeOf(list, prefs) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                activeOf(installed.value, settings.preferences.value),
            )

    /** Re-read the directory. Cheap: a listing plus 127 bytes per file. */
    fun refresh() {
        installed.value = scan()
    }

    /**
     * Make [name] the archive the maps use. Ignored if no such archive is installed, so a stale
     * choice can never blank the map.
     */
    fun select(name: String) {
        if (installed.value.none { it.name == name }) return
        settings.setMapArchive(name)
    }

    /**
     * Copy a region pack in from [open], reporting bytes copied as it goes.
     *
     * @param displayName the picked file's name, used to name the installed copy.
     * @param declaredSize the size the picker reported, or null when it did not. Used only to
     *   refuse an import that cannot fit — a 1.3 GB archive onto a phone with 400 MB free should
     *   fail immediately rather than after ten minutes of copying.
     *
     * The copy goes to a `.part` file that [scan] ignores, and is renamed only once it is whole.
     * A partial copy is therefore never visible as an archive — which matters here, because the
     * fallback picks the *largest* file and a half-copied country build would win that contest.
     * Cancelling or failing removes the partial file.
     */
    suspend fun install(
        displayName: String,
        declaredSize: Long?,
        open: suspend () -> InputStream?,
        onProgress: (copied: Long) -> Unit = {},
    ): InstallOutcome = withContext(ioDispatcher) {
        if (!tilesDir.exists()) tilesDir.mkdirs()

        val available = tilesDir.usableSpace
        if (declaredSize != null && declaredSize > available) {
            return@withContext InstallOutcome.NotEnoughSpace(declaredSize, available)
        }

        val target = freeName(displayName)
        val part = File(tilesDir, target.name + ".part")

        try {
            val stream = open() ?: return@withContext InstallOutcome.Failed
            stream.use { input ->
                val header = ByteArray(MapTiles.HEADER_SIZE)
                if (!input.readFully(header) || !MapTiles.isArchiveHeader(header)) {
                    return@withContext InstallOutcome.NotAnArchive
                }
                part.outputStream().buffered().use { output ->
                    output.write(header)
                    var copied = header.size.toLong()
                    onProgress(copied)

                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                }
            }
            if (!part.renameTo(target)) return@withContext InstallOutcome.Failed
        } catch (t: Throwable) {
            // Includes cancellation: a half-copied gigabyte is never left behind, whether the user
            // walked away from the screen or the storage filled up.
            part.delete()
            throw t
        }

        refresh()
        val archive = installed.value.firstOrNull { it.name == target.name }
            ?: return@withContext InstallOutcome.Failed
        // A freshly imported pack is the one the user wants to look at.
        settings.setMapArchive(archive.name)
        InstallOutcome.Installed(archive)
    }

    /**
     * Remove an installed archive. Returns false when it was already gone.
     *
     * Deliberately not guarded by "is it the one in use": the user is looking at a list they chose
     * from, the screen confirms first, and a map with nothing to render falls back to the route
     * over a blank background — the same state a fresh install is in.
     */
    suspend fun delete(name: String): Boolean = withContext(ioDispatcher) {
        val archive = installed.value.firstOrNull { it.name == name } ?: return@withContext false
        val deleted = archive.file.delete()
        refresh()
        if (settings.preferences.value.mapArchive == name) {
            settings.setMapArchive(installed.value.firstOrNull()?.name)
        }
        deleted
    }

    /** The directory as it stands, largest archive first. */
    private fun scan(): List<InstalledArchive> {
        if (!tilesDir.exists()) tilesDir.mkdirs()
        return tilesDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith("." + MapTiles.ARCHIVE_EXTENSION) }
            .sortedByDescending { it.length() }
            .map {
                InstalledArchive(file = it, sizeBytes = it.length(), coverage = MapTiles.coverage(it))
            }
    }

    /**
     * [displayName] sanitised, given the archive extension, and made unique in the directory.
     *
     * The user's own name is kept as far as it safely can be — this is what the Map data screen
     * lists, and a pack called "Česko" should not appear as "esko". Only characters that are
     * genuinely unsafe in a path are replaced.
     */
    private fun freeName(displayName: String): File {
        val extension = "." + MapTiles.ARCHIVE_EXTENSION
        val stem = displayName
            .let { if (it.endsWith(extension, ignoreCase = true)) it.dropLast(extension.length) else it }
            .filter { it.code >= FIRST_PRINTABLE }
            .replace(UNSAFE_NAME_CHARS, "-")
            .trim('-', '.', ' ')
            .ifEmpty { DEFAULT_NAME }

        var candidate = File(tilesDir, stem + "." + MapTiles.ARCHIVE_EXTENSION)
        var n = 2
        while (candidate.exists()) {
            candidate = File(tilesDir, stem + "-" + n + "." + MapTiles.ARCHIVE_EXTENSION)
            n++
        }
        return candidate
    }

    companion object {
        private const val COPY_BUFFER = 1 shl 16

        /** Below this a character is a control code, which has no business in a file name. */
        private const val FIRST_PRINTABLE = 0x20

        private const val DEFAULT_NAME = "region"

        /**
         * Path separators, the characters common file systems reserve, and runs of whitespace.
         * Everything else — letters in any script, digits, brackets — is kept. Control codes are
         * dropped separately, before this runs.
         */
        private val UNSAFE_NAME_CHARS = Regex("""[\\/:*?"<>|\s]+""")

        @Volatile private var instance: MapArchiveStore? = null

        fun get(context: Context): MapArchiveStore {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: MapArchiveStore(
                    tilesDir = MapTiles.tilesDir(appContext),
                    settings = PrefsAppSettings.get(appContext),
                ).also { instance = it }
            }
        }

        private fun activeOf(list: List<InstalledArchive>, prefs: AppPreferences) =
            list.firstOrNull { it.name == prefs.mapArchive } ?: list.firstOrNull()
    }
}

/** Fills [buffer] completely, or returns false if the stream ended first. */
private fun InputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read <= 0) return false
        offset += read
    }
    return true
}
