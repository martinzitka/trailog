package io.github.martinzitka.trailog.ui.map

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Locates the PMTiles archive on the device and turns the bundled style template into a style
 * MapLibre can load.
 *
 * The archive is not in the APK — it is hundreds of megabytes (the Czech build is 1.3 GB), well
 * past Play's base APK limit. It is built by `infra/tiles/build-tiles.sh` and then either imported
 * through Settings › Map data or pushed with adb; both land in the same directory.
 *
 * This object knows the *format*: where archives live, what a PMTiles header says, and how the
 * bundled style template becomes a loadable style. Which archives are installed and which one is
 * in use belongs to [MapArchiveStore].
 */
object MapTiles {

    private const val TAG = "TrailogMap"

    /** PMTiles v3 header: fixed 127-byte little-endian struct. Public so an import can read it. */
    const val HEADER_SIZE = 127
    private const val PMTILES_V3 = 3
    private const val BOUNDS_OFFSET = 102
    private const val E7 = 1e7
    private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

    /** Sub-directory of the app's external files dir holding installed region archives. */
    private const val TILES_DIR = "tiles"

    /** Extension every installed archive carries. Also what the file picker's result is renamed to. */
    const val ARCHIVE_EXTENSION = "pmtiles"

    /**
     * The directory installed archives live in, created if absent.
     *
     * Creating it ourselves matters more than it looks. If it is instead created by `adb shell
     * mkdir` during development it belongs to the shell user, and the app then cannot read it at
     * all — `listFiles()` returns null and the map silently falls back to the polyline with no
     * error anywhere. Owning the directory is what makes the documented adb push work, and the
     * in-app import writes to the same place.
     */
    fun tilesDir(context: Context): File =
        File(context.getExternalFilesDir(null), TILES_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Whether [header] opens a PMTiles v3 archive: the 7-byte magic followed by a version byte.
     *
     * Exposed so an import can reject the wrong file after reading 127 bytes of it, rather than
     * copying a gigabyte first and discovering the mistake at the end.
     */
    fun isArchiveHeader(header: ByteArray): Boolean =
        header.size >= HEADER_SIZE &&
            header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) &&
            header[7].toInt() == PMTILES_V3

    /**
     * Placeholder written into the bundled style by `infra/tiles/fetch-style-assets.sh`.
     *
     * The tile URL cannot be baked into the asset: the archive path depends on the device, and a
     * streamed archive depends on the user's server. So the style ships as a *template* and the
     * real URL is substituted here.
     */
    private const val TILE_URL_PLACEHOLDER = "__TRAILOG_TILE_URL__"

    /**
     * Style metadata key naming every layer that draws a path or trail — footways, cycleways,
     * bridleways, the MTB underlay, steps, and their bridge and tunnel variants, plus the path
     * label layer.
     *
     * The group is declared in the style rather than listed in Kotlin because the style is what
     * knows which layers it split trails into: the fork already added four of them and a later
     * one will add more. `BundledStyleTest` asserts every declared id exists, which is what stops
     * the two drifting apart.
     *
     * Forest tracks (`trailog-track*`) are deliberately **not** in the group. They are roads —
     * driveable, and the way most rides leave a village — so hiding them under a control labelled
     * "paths and trails" would remove things the user still needs to navigate by.
     */
    private const val TRAIL_LAYERS_KEY = "trailog:trailLayers"

    /**
     * The MapLibre tile URL for a local archive.
     *
     * `pmtiles://` is read natively by MapLibre Android 11.7.0+, so no custom protocol handler
     * is needed. The same scheme also fronts an https URL when streaming from the user's own
     * server (`pmtiles://https://host/file.pmtiles`).
     */
    fun tileUrl(archive: File): String = "pmtiles://file://${archive.absolutePath}"

    /**
     * The geographic area an archive covers, in WGS84 degrees.
     *
     * Read from the file rather than hardcoded, so a user who builds a different region gets a
     * map that opens over *their* data.
     */
    data class Coverage(
        val minLatitude: Double,
        val minLongitude: Double,
        val maxLatitude: Double,
        val maxLongitude: Double,
    )

    /**
     * Reads the coverage bounds from a PMTiles v3 header, or null if the file is not a readable
     * PMTiles archive.
     *
     * The header is a fixed 127-byte little-endian struct: a 7-byte magic, a version byte, and
     * the bounds as four int32 values in degrees x 10^7 starting at offset 102. Reading 127
     * bytes is cheap even for a 1.3 GB archive.
     *
     * Null is an ordinary answer, not an error — the caller simply has no better camera
     * position than MapLibre's default.
     */
    fun coverage(archive: File): Coverage? = runCatching {
        val header = ByteArray(HEADER_SIZE)
        archive.inputStream().use { stream ->
            if (stream.read(header) != HEADER_SIZE) return null
        }
        coverageOf(header)
    }.getOrNull()

    /**
     * The coverage declared by an already-read 127-byte header, or null if those bytes do not
     * open a PMTiles v3 archive. The import reads the header once and asks both questions of it.
     */
    fun coverageOf(header: ByteArray): Coverage? {
        if (!isArchiveHeader(header)) return null

        fun int32At(offset: Int): Int =
            (header[offset].toInt() and 0xFF) or
                ((header[offset + 1].toInt() and 0xFF) shl 8) or
                ((header[offset + 2].toInt() and 0xFF) shl 16) or
                ((header[offset + 3].toInt() and 0xFF) shl 24)

        return Coverage(
            minLongitude = int32At(BOUNDS_OFFSET) / E7,
            minLatitude = int32At(BOUNDS_OFFSET + 4) / E7,
            maxLongitude = int32At(BOUNDS_OFFSET + 8) / E7,
            maxLatitude = int32At(BOUNDS_OFFSET + 12) / E7,
        ).takeIf { it.minLatitude < it.maxLatitude && it.minLongitude < it.maxLongitude }
    }

    /**
     * Reads the bundled style and substitutes [tileUrl] for the placeholder.
     *
     * @throws IllegalStateException if the placeholder is absent, which means the asset and
     *   `fetch-style-assets.sh` have drifted apart — a build-time mistake worth failing on
     *   rather than rendering a map with no tiles and no explanation.
     */
    fun loadStyleJson(context: Context, tileUrl: String, assetPath: String): String {
        val template = context.assets.open(assetPath).use { it.readBytes().decodeToString() }
        check(template.contains(TILE_URL_PLACEHOLDER)) {
            "Bundled style $assetPath has no $TILE_URL_PLACEHOLDER placeholder; " +
                "re-run infra/tiles/fetch-style-assets.sh"
        }
        return template.replace(TILE_URL_PLACEHOLDER, tileUrl)
    }

    /**
     * The ids of the style's trail layers, read from its `metadata`.
     *
     * Empty when the style declares no group. That is a drift between the asset and this code
     * rather than a user-visible failure — the map still renders, the trail toggle simply stops
     * doing anything — so it logs instead of throwing, and `BundledStyleTest` is what fails
     * loudly in CI.
     */
    fun trailLayerIds(styleJson: String): List<String> {
        val declared = runCatching {
            val array = JSONObject(styleJson)
                .getJSONObject("metadata")
                .getJSONArray(TRAIL_LAYERS_KEY)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())

        if (declared.isEmpty()) {
            Log.w(
                TAG,
                "Bundled style declares no $TRAIL_LAYERS_KEY; the trail toggle will do nothing",
            )
        }
        return declared
    }
}
