package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.datetime.Instant
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.math.BigDecimal
import javax.xml.parsers.SAXParserFactory

/**
 * GPX 1.1 reading and writing for tracks. This is the *single* GPX implementation in the
 * project (CLAUDE.md): the phone, the server and the importer all parse and write GPX through
 * here, so a track can never be interpreted two different ways.
 *
 * **Reading uses SAX, not StAX.** `javax.xml.stream` (StAX) is absent on Android — referencing
 * it throws `NoClassDefFoundError` at class load on a device — whereas SAX (`javax.xml.parsers`
 * / `org.xml.sax`) ships on both Android and the desktop JVM. `:core` must run on all three
 * targets, so SAX is the portable choice. Writing is a plain string builder for the same reason
 * and because the output is small and fully under our control.
 *
 * **Segments are first-class.** Each `<trkseg>` maps to a run of points sharing a
 * [RawPoint.segmentIndex], and the boundary between two `<trkseg>` elements is preserved on
 * read and re-emitted on write. This is what stops a signal blackout or a reboot gap from
 * being silently welded into one continuous line (CLAUDE.md).
 *
 * **Lossless for Trailog's own data.** GPX core carries only lat/lon/ele/time, but a raw fix
 * also has accuracy, speed, bearing and pressure. Those are written under a Trailog extension
 * namespace ([TRAILOG_NS]) so a phone-recorded activity round-trips through GPX without losing
 * a field, while remaining valid GPX that any other tool reads by ignoring the extensions. See
 * `docs/adr/0005-gpx-extensions.md`. The reader is deliberately tolerant: a plain GPX from
 * another app (ele + time only, or foreign extensions like heart rate) parses fine, with the
 * absent fields left null.
 *
 * The reader is hardened against XXE — DTDs and external entities are disabled.
 */
object Gpx {

    /** XML namespace for Trailog's per-point extensions. A URN, not a URL: nothing is fetched. */
    const val TRAILOG_NS: String = "urn:trailog:gpx:v1"

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val DEFAULT_CREATOR = "Trailog"

    private val parserFactory: SAXParserFactory =
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE: no DTDs, no external entities. Wrapped because a given parser
            // may not recognise every feature name; the DOCTYPE ban is the important one.
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            // Not touching setXIncludeAware: Android's default factory throws
            // UnsupportedOperationException from it, and XInclude is off by default anyway.
        }

    private fun SAXParserFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Feature unsupported by this parser; ignore.
        }
    }

    // ---- Reading ---------------------------------------------------------------------------

    /**
     * Parses every `<trk>` in a GPX document. Points are returned in file order with their
     * segment ordinal set from the enclosing `<trkseg>`. Matching is by local element name, so
     * GPX 1.0 and 1.1 and namespaced variants all read the same.
     *
     * @throws GpxParseException if the XML is malformed or a coordinate is unparseable.
     */
    fun read(xml: String): List<GpxTrack> {
        val handler = GpxHandler()
        try {
            parserFactory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        } catch (e: SAXException) {
            // Domain errors are thrown from the handler wrapped in a SAXException; unwrap them.
            throw (e.cause as? GpxParseException)
                ?: GpxParseException("malformed GPX: ${e.message}", e)
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            throw GpxParseException("malformed GPX: ${e.message}", e)
        }
        return handler.tracks
    }

    /**
     * SAX handler mirroring the GPX structure. Text of leaf elements is accumulated in [text]
     * (SAX may deliver character data in several chunks) and consumed on the closing tag.
     */
    private class GpxHandler : DefaultHandler() {
        val tracks = ArrayList<GpxTrack>()

        private var name: String? = null
        private var type: String? = null
        private var points = ArrayList<RawPoint>()
        private var segmentIndex = -1
        private var inTrack = false
        private var inPoint = false

        private var lat = 0.0
        private var lon = 0.0
        private var ele: Double? = null
        private var time: Instant? = null
        private var accuracy: Double? = null
        private var speed: Double? = null
        private var bearing: Double? = null
        private var pressure: Double? = null

        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            text.setLength(0)
            when (localOrQ(localName, qName)) {
                "trk" -> {
                    inTrack = true
                    name = null
                    type = null
                    points = ArrayList()
                    segmentIndex = -1
                }
                "trkseg" -> segmentIndex++
                "trkpt" -> {
                    inPoint = true
                    // A trkpt outside any trkseg (some minimal files) is segment 0.
                    if (segmentIndex < 0) segmentIndex = 0
                    lat = requiredAttr(attributes, "lat")
                    lon = requiredAttr(attributes, "lon")
                    ele = null; time = null
                    accuracy = null; speed = null; bearing = null; pressure = null
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val value = text.toString().trim()
            when (localOrQ(localName, qName)) {
                // Track-level metadata, only when not inside a point.
                "name" -> if (inTrack && !inPoint) name = value
                "type" -> if (inTrack && !inPoint) type = value

                // Point-level leaves. `speed`/`course` are also core GPX 1.0 fields.
                "ele" -> if (inPoint) ele = parseDouble(value)
                "time" -> if (inPoint) time = parseTime(value)
                "accuracy" -> if (inPoint) accuracy = parseDouble(value)
                "speed" -> if (inPoint) speed = parseDouble(value)
                "bearing", "course" -> if (inPoint) bearing = parseDouble(value)
                "pressure" -> if (inPoint) pressure = parseDouble(value)

                "trkpt" -> {
                    val t = time ?: fail("<trkpt> is missing <time>")
                    points.add(
                        RawPoint(
                            latitude = lat,
                            longitude = lon,
                            altitude = ele,
                            accuracy = accuracy,
                            time = t,
                            speed = speed,
                            bearing = bearing,
                            pressure = pressure,
                            segmentIndex = segmentIndex,
                        ),
                    )
                    inPoint = false
                }
                "trk" -> {
                    tracks.add(GpxTrack(name = name, type = type, points = points))
                    inTrack = false
                }
            }
            text.setLength(0)
        }

        /** Prefer the namespace local name; fall back to the raw qName minus any prefix. */
        private fun localOrQ(localName: String?, qName: String?): String =
            if (!localName.isNullOrEmpty()) localName else (qName ?: "").substringAfterLast(':')

        private fun requiredAttr(attributes: Attributes, name: String): Double {
            val raw = attributes.getValue(name) ?: attributes.getValue("", name)
                ?: fail("<trkpt> is missing the '$name' attribute")
            return raw.toDoubleOrNull() ?: fail("<trkpt> has non-numeric $name")
        }

        private fun parseDouble(raw: String): Double? {
            if (raw.isEmpty()) return null
            return raw.toDoubleOrNull() ?: fail("non-numeric value")
        }

        private fun parseTime(raw: String): Instant =
            try {
                Instant.parse(raw)
            } catch (e: Exception) {
                fail("unparseable <time>")
            }

        /** Raise a domain error from inside SAX callbacks. Unwrapped by [read]. Carries no coords. */
        private fun fail(message: String): Nothing = throw SAXException(GpxParseException(message))
    }

    // ---- Writing ---------------------------------------------------------------------------

    /** Serializes a single track. See [write]. */
    fun write(track: GpxTrack, creator: String = DEFAULT_CREATOR): String =
        write(listOf(track), creator)

    /**
     * Serializes tracks to a GPX 1.1 document. Points are grouped into `<trkseg>` by
     * [RawPoint.segmentIndex] in ascending order; within a segment, points are emitted in
     * ascending time so output is deterministic. Non-core fields are written under
     * [TRAILOG_NS]. The result is valid GPX that opens in any third-party tool.
     */
    fun write(tracks: List<GpxTrack>, creator: String = DEFAULT_CREATOR): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append('\n')
        sb.append("<gpx version=\"1.1\" creator=\"").append(esc(creator)).append('"')
        sb.append(" xmlns=\"").append(GPX_NS).append('"')
        sb.append(" xmlns:trailog=\"").append(TRAILOG_NS).append("\">")

        for (track in tracks) {
            nl(sb, 1); sb.append("<trk>")
            if (track.name != null) {
                nl(sb, 2); sb.append("<name>").append(esc(track.name)).append("</name>")
            }
            if (track.type != null) {
                nl(sb, 2); sb.append("<type>").append(esc(track.type)).append("</type>")
            }
            for (seg in track.points.groupBy { it.segmentIndex }.toSortedMap().values) {
                nl(sb, 2); sb.append("<trkseg>")
                for (p in seg.sortedBy { it.time }) writePoint(sb, p)
                nl(sb, 2); sb.append("</trkseg>")
            }
            nl(sb, 1); sb.append("</trk>")
        }

        nl(sb, 0); sb.append("</gpx>")
        return sb.toString()
    }

    private fun writePoint(sb: StringBuilder, p: RawPoint) {
        nl(sb, 3)
        sb.append("<trkpt lat=\"").append(num(p.latitude)).append("\" lon=\"").append(num(p.longitude)).append("\">")
        if (p.altitude != null) {
            nl(sb, 4); sb.append("<ele>").append(num(p.altitude)).append("</ele>")
        }
        nl(sb, 4); sb.append("<time>").append(p.time.toString()).append("</time>")

        // Trailog extensions — only emitted for fields that are actually present.
        if (p.accuracy != null || p.speed != null || p.bearing != null || p.pressure != null) {
            nl(sb, 4); sb.append("<extensions>")
            ext(sb, "accuracy", p.accuracy)
            ext(sb, "speed", p.speed)
            ext(sb, "bearing", p.bearing)
            ext(sb, "pressure", p.pressure)
            nl(sb, 4); sb.append("</extensions>")
        }

        nl(sb, 3); sb.append("</trkpt>")
    }

    private fun ext(sb: StringBuilder, name: String, value: Double?) {
        if (value == null) return
        nl(sb, 5)
        sb.append("<trailog:").append(name).append('>').append(num(value)).append("</trailog:").append(name).append('>')
    }

    /**
     * Formats a double as a plain decimal string — never scientific notation (which some
     * strict GPX parsers reject) and never a locale comma. [BigDecimal.valueOf] uses
     * [Double.toString]'s shortest round-tripping form, so the value survives read-back exactly.
     */
    private fun num(value: Double): String = BigDecimal.valueOf(value).toPlainString()

    /** Newline + two-space indent per depth level, for human-readable output. */
    private fun nl(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    /** XML-escapes text and attribute content. Numbers and ISO timestamps need no escaping. */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

/** Thrown when a GPX document cannot be parsed. Carries no coordinates (CLAUDE.md: none in logs). */
class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
