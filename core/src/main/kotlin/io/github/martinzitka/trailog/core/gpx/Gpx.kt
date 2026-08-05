package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.datetime.Instant
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.XMLStreamWriter

/**
 * GPX 1.1 reading and writing for tracks. This is the *single* GPX implementation in the
 * project (CLAUDE.md): the phone, the server and the importer all parse and write GPX through
 * here, so a track can never be interpreted two different ways.
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
 * The reader is hardened against XXE — external entities and DTDs are disabled.
 */
object Gpx {

    /** XML namespace for Trailog's per-point extensions. A URN, not a URL: nothing is fetched. */
    const val TRAILOG_NS: String = "urn:trailog:gpx:v1"

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val DEFAULT_CREATOR = "Trailog"

    private val inputFactory: XMLInputFactory =
        XMLInputFactory.newFactory().apply {
            // Harden against XXE: no external entities, no DTDs.
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }

    private val outputFactory: XMLOutputFactory = XMLOutputFactory.newFactory()

    // ---- Reading ---------------------------------------------------------------------------

    /**
     * Parses every `<trk>` in a GPX document. Points are returned in file order with their
     * segment ordinal set from the enclosing `<trkseg>`. Matching is by local element name, so
     * GPX 1.0 and 1.1 and namespaced variants all read the same.
     *
     * @throws GpxParseException if the XML is malformed or a coordinate is unparseable.
     */
    fun read(xml: String): List<GpxTrack> {
        val reader = inputFactory.createXMLStreamReader(StringReader(xml))
        val tracks = ArrayList<GpxTrack>()
        try {
            // Per-track state.
            var name: String? = null
            var type: String? = null
            var points = ArrayList<RawPoint>()
            var segmentIndex = -1
            var inTrack = false

            // Per-point state, valid only between <trkpt> and </trkpt>.
            var inPoint = false
            var lat = 0.0
            var lon = 0.0
            var ele: Double? = null
            var time: Instant? = null
            var accuracy: Double? = null
            var speed: Double? = null
            var bearing: Double? = null
            var pressure: Double? = null

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
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
                            lat = requiredAttr(reader, "lat")
                            lon = requiredAttr(reader, "lon")
                            ele = null; time = null
                            accuracy = null; speed = null; bearing = null; pressure = null
                        }

                        // Track-level metadata, only when not inside a point.
                        "name" -> if (inTrack && !inPoint) name = reader.elementText.trim()
                        "type" -> if (inTrack && !inPoint) type = reader.elementText.trim()

                        // Point-level leaves. `speed`/`course` are also core GPX 1.0 fields.
                        "ele" -> if (inPoint) ele = reader.doubleText()
                        "time" -> if (inPoint) time = parseTime(reader.elementText.trim())
                        "accuracy" -> if (inPoint) accuracy = reader.doubleText()
                        "speed" -> if (inPoint) speed = reader.doubleText()
                        "bearing", "course" -> if (inPoint) bearing = reader.doubleText()
                        "pressure" -> if (inPoint) pressure = reader.doubleText()
                    }

                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "trkpt" -> {
                            val t = time
                                ?: throw GpxParseException("<trkpt> at lat=$lat is missing <time>")
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
                }
            }
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            throw GpxParseException("malformed GPX: ${e.message}", e)
        } finally {
            reader.close()
        }
        return tracks
    }

    private fun requiredAttr(reader: XMLStreamReader, name: String): Double {
        val raw = reader.getAttributeValue(null, name)
            ?: throw GpxParseException("<trkpt> is missing the '$name' attribute")
        return raw.toDoubleOrNull()
            ?: throw GpxParseException("<trkpt> has non-numeric $name='$raw'")
    }

    private fun XMLStreamReader.doubleText(): Double? {
        val raw = elementText.trim()
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()
            ?: throw GpxParseException("non-numeric value '$raw'")
    }

    private fun parseTime(raw: String): Instant =
        try {
            Instant.parse(raw)
        } catch (e: Exception) {
            throw GpxParseException("unparseable <time> '$raw'", e)
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
        val out = StringWriter()
        val w = outputFactory.createXMLStreamWriter(out)
        try {
            w.writeStartDocument("UTF-8", "1.0")
            w.nl(0)
            w.writeStartElement("gpx")
            w.writeAttribute("version", "1.1")
            w.writeAttribute("creator", creator)
            w.writeNamespace("", GPX_NS)
            w.writeNamespace("trailog", TRAILOG_NS)

            for (track in tracks) {
                w.nl(1)
                w.writeStartElement("trk")
                if (track.name != null) {
                    w.nl(2); w.writeStartElement("name"); w.writeCharacters(track.name); w.writeEndElement()
                }
                if (track.type != null) {
                    w.nl(2); w.writeStartElement("type"); w.writeCharacters(track.type); w.writeEndElement()
                }
                for (seg in track.points.groupBy { it.segmentIndex }.toSortedMap().values) {
                    w.nl(2)
                    w.writeStartElement("trkseg")
                    for (p in seg.sortedBy { it.time }) writePoint(w, p)
                    w.nl(2)
                    w.writeEndElement() // trkseg
                }
                w.nl(1)
                w.writeEndElement() // trk
            }

            w.nl(0)
            w.writeEndElement() // gpx
            w.writeEndDocument()
        } finally {
            w.close()
        }
        return out.toString()
    }

    private fun writePoint(w: XMLStreamWriter, p: RawPoint) {
        w.nl(3)
        w.writeStartElement("trkpt")
        w.writeAttribute("lat", num(p.latitude))
        w.writeAttribute("lon", num(p.longitude))
        if (p.altitude != null) {
            w.nl(4); w.writeStartElement("ele"); w.writeCharacters(num(p.altitude)); w.writeEndElement()
        }
        w.nl(4); w.writeStartElement("time"); w.writeCharacters(p.time.toString()); w.writeEndElement()

        // Trailog extensions — only emitted for fields that are actually present.
        if (p.accuracy != null || p.speed != null || p.bearing != null || p.pressure != null) {
            w.nl(4)
            w.writeStartElement("extensions")
            ext(w, "accuracy", p.accuracy)
            ext(w, "speed", p.speed)
            ext(w, "bearing", p.bearing)
            ext(w, "pressure", p.pressure)
            w.nl(4)
            w.writeEndElement() // extensions
        }

        w.nl(3)
        w.writeEndElement() // trkpt
    }

    private fun ext(w: XMLStreamWriter, name: String, value: Double?) {
        if (value == null) return
        w.nl(5)
        w.writeStartElement(TRAILOG_NS, name)
        w.writeCharacters(num(value))
        w.writeEndElement()
    }

    /**
     * Formats a double as a plain decimal string — never scientific notation (which some
     * strict GPX parsers reject) and never a locale comma. [BigDecimal.valueOf] uses
     * [Double.toString]'s shortest round-tripping form, so the value survives read-back exactly.
     */
    private fun num(value: Double): String = BigDecimal.valueOf(value).toPlainString()

    /** Newline + two-space indent per depth level, for human-readable output. */
    private fun XMLStreamWriter.nl(depth: Int) = writeCharacters("\n" + "  ".repeat(depth))
}

/** Thrown when a GPX document cannot be parsed. Carries no coordinates (CLAUDE.md: none in logs). */
class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
