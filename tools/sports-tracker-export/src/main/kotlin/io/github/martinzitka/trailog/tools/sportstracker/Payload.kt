package io.github.martinzitka.trailog.tools.sportstracker

/**
 * What a downloaded body actually turned out to be.
 *
 * The endpoints this tool calls are undocumented and unversioned, and an authentication failure
 * or a retired route is far more likely to arrive as HTTP 200 carrying a login page than as a
 * 4xx. Sniffing the bytes is therefore the only trustworthy check that an export succeeded —
 * the status code and the `Content-Type` header are both hearsay.
 */
enum class PayloadFormat {
    /** A real FIT file: the `.FIT` signature is present at the documented offset. */
    FIT,

    /** XML whose root element is `gpx`. */
    GPX,

    /** XML, but not GPX. Almost always an error document. */
    XML_OTHER,

    /** A JSON object or array — how this API reports most errors. */
    JSON,

    /** HTML. Effectively always a login page or an error page, never an export. */
    HTML,

    /** Zero bytes. */
    EMPTY,

    /** Bytes that match nothing known. Keep the file and look at it by hand. */
    UNKNOWN,
}

/**
 * Identifies a downloaded body by content, never by the response headers.
 *
 * Pure and byte-oriented so it is testable without a network, which matters because the network
 * half of this tool cannot be tested at all — see the README.
 */
object PayloadSniffer {

    /** ASCII `.FIT`, the signature every FIT file carries at bytes 8..11. */
    private val FIT_SIGNATURE = byteArrayOf(0x2E, 0x46, 0x49, 0x54)

    /**
     * The FIT header is 12 or 14 bytes; the 14-byte form adds a CRC. Any other value means the
     * signature match was a coincidence in some other format's payload.
     */
    private val FIT_HEADER_SIZES = setOf(12, 14)

    fun sniff(bytes: ByteArray): PayloadFormat {
        if (bytes.isEmpty()) return PayloadFormat.EMPTY
        if (isFit(bytes)) return PayloadFormat.FIT

        // Decode only the head. The body may be megabytes and may not be text at all; a decode
        // of arbitrary bytes as UTF-8 cannot throw, it just produces replacement characters,
        // which match none of the markers below.
        val head = bytes.take(2048).toByteArray().toString(Charsets.UTF_8)
            .removePrefix("﻿")
            .trimStart()
        val lower = head.lowercase()

        return when {
            lower.startsWith("<!doctype html") || lower.startsWith("<html") -> PayloadFormat.HTML
            lower.startsWith("{") || lower.startsWith("[") -> PayloadFormat.JSON
            lower.startsWith("<?xml") || lower.startsWith("<") ->
                // The XML declaration and any comments sit ahead of the root element, so look for
                // the tag rather than requiring it at position zero.
                if (lower.contains("<gpx")) PayloadFormat.GPX else PayloadFormat.XML_OTHER
            else -> PayloadFormat.UNKNOWN
        }
    }

    /**
     * True when [bytes] are the format that was actually requested.
     *
     * The one place "did this export work?" is decided, so the answer cannot drift between the
     * fresh-download path and the resume path that re-checks files already on disk.
     */
    fun matches(bytes: ByteArray, requested: ExportFormat): Boolean = when (requested) {
        ExportFormat.GPX -> sniff(bytes) == PayloadFormat.GPX
        ExportFormat.FIT -> sniff(bytes) == PayloadFormat.FIT
    }

    /**
     * Counts `<trkpt` opening tags directly in the bytes.
     *
     * Byte-level rather than decoding to a String first: this runs over the whole archive, and
     * some of these files are megabytes. ASCII tag names cannot appear spuriously inside UTF-8
     * multi-byte sequences, so scanning raw bytes is safe as well as cheaper.
     */
    fun countTrackPoints(bytes: ByteArray): Int {
        val needle = "<trkpt".toByteArray(Charsets.US_ASCII)
        var count = 0
        var i = 0
        outer@ while (i <= bytes.size - needle.size - 1) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            // A tag name ends at whitespace, '>' or '/'; anything else means a longer name.
            when (bytes[i + needle.size].toInt().toChar()) {
                ' ', '\t', '\r', '\n', '>', '/' -> count++
            }
            i += needle.size
        }
        return count
    }

    /** True when [bytes] carry the FIT signature under a plausible header size. */
    fun isFit(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize !in FIT_HEADER_SIZES) return false
        return FIT_SIGNATURE.indices.all { bytes[8 + it] == FIT_SIGNATURE[it] }
    }

    /**
     * The data size the FIT header claims, in bytes — the payload between the header and the
     * trailing CRC. Null when [bytes] are not FIT.
     *
     * Worth reporting from the probe because it is the cheapest signal that a FIT export carries
     * more than a GPX one: a file materially larger than its own track geometry needs is
     * carrying extra message types. It is evidence, not proof — see the README on what actually
     * settles whether heart rate is in there.
     */
    fun fitDataSize(bytes: ByteArray): Long? {
        if (!isFit(bytes)) return null
        // Little-endian uint32 at offset 4.
        var value = 0L
        for (i in 3 downTo 0) {
            value = (value shl 8) or (bytes[4 + i].toLong() and 0xFF)
        }
        return value
    }
}

/**
 * How much per-point sensor data a GPX body carries, counted without retaining any of it.
 *
 * This is the half of the FIT question that the plan says has to be answered before a FIT
 * decoder is worth building: FIT is only preferable *if the historical workouts actually contain*
 * heart rate or cadence. A GPX export that carries none is real evidence about what Sports
 * Tracker holds for that workout.
 *
 * Counts only. No coordinate, and no sensor reading, is ever returned or logged (CLAUDE.md).
 */
data class GpxSensorScan(
    val trackPoints: Int,
    val extensionElements: Int,
    val heartRate: Int,
    val cadence: Int,
    val temperature: Int,
    val power: Int,
) {
    val hasAnySensorData: Boolean
        get() = heartRate > 0 || cadence > 0 || temperature > 0 || power > 0

    companion object {
        /**
         * Counts sensor elements by substring rather than by parsing. Deliberate: this runs on a
         * body that may not be valid XML at all, and a scan that throws on malformed input would
         * be useless exactly when the answer matters. `:core`'s real parser is not used here —
         * this tool never depends on `:core`.
         *
         * Matching ignores namespace prefixes, since Garmin's `TrackPointExtension` is written
         * as `gpxtpx:hr` by most tools but the prefix is arbitrary.
         */
        fun of(gpx: String): GpxSensorScan = GpxSensorScan(
            trackPoints = countTags(gpx, "trkpt"),
            extensionElements = countTags(gpx, "extensions"),
            heartRate = countTags(gpx, "hr"),
            cadence = countTags(gpx, "cad"),
            temperature = countTags(gpx, "atemp"),
            power = countTags(gpx, "power"),
        )

        /**
         * Counts opening tags named [name], with or without a namespace prefix.
         *
         * The trailing character class is what stops `hr` from also matching `hrm` and `trkpt`
         * from matching a hypothetical `trkptx`: a tag name ends at whitespace, `>` or `/`.
         */
        private fun countTags(xml: String, name: String): Int =
            Regex("""<(?:[A-Za-z0-9_.-]+:)?${Regex.escape(name)}[\s/>]""")
                .findAll(xml)
                .count()
    }
}
