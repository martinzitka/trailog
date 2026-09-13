package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.SensorSample
import io.github.martinzitka.trailog.core.model.SensorStream
import io.github.martinzitka.trailog.core.model.SensorType
import kotlinx.datetime.Instant
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.math.BigDecimal
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToLong

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
 * **Sensor samples are a track-level stream, written twice on purpose.** Heart rate and its kin
 * are their own timestamped stream rather than fields on a fix, and GPX has no place to express
 * that: the standard `gpxtpx:` elements hang off a `<trkpt>`, so a reading taken during a signal
 * blackout has nowhere to go, and a reading taken 0.4 s after a fix would have to be pretended
 * simultaneous with it. So the writer emits both — the exact stream under [TRAILOG_NS], which is
 * what the reader prefers and what makes a Trailog activity round-trip losslessly, and a lossy
 * projection onto the nearest track point in Garmin's namespaces, so the file is portable to tools
 * that have never heard of Trailog. Data portability is non-negotiable (CLAUDE.md), and a
 * heart rate only Trailog can read is not portable. See `docs/adr/0023-sensor-sample-units.md`.
 *
 * The reader is hardened against XXE — DTDs and external entities are disabled.
 */
object Gpx {

    /** XML namespace for Trailog's per-point extensions. A URN, not a URL: nothing is fetched. */
    const val TRAILOG_NS: String = "urn:trailog:gpx:v1"

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val DEFAULT_CREATOR = "Trailog"

    /** Garmin's TrackPointExtension — the de facto standard for per-point hr, cadence and temp. */
    private const val GPXTPX_NS = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"

    /** Garmin's PowerExtension. Separate from [GPXTPX_NS] because power is not in that schema. */
    private const val GPXPX_NS = "http://www.garmin.com/xmlschemas/PowerExtension/v1"

    /**
     * Sensor types the standard extensions can express, and the element name each uses. Types
     * absent here still round-trip through [TRAILOG_NS]; they simply have no portable form.
     */
    private val GARMIN_ELEMENT: Map<SensorType, String> = mapOf(
        SensorType.HEART_RATE to "hr",
        SensorType.CADENCE to "cad",
        SensorType.TEMPERATURE to "atemp",
        SensorType.POWER to "PowerInWatts",
    )

    /** Local element names the reader accepts as a sensor reading inside a `<trkpt>`. */
    private val SENSOR_ELEMENT: Map<String, SensorType> = mapOf(
        "hr" to SensorType.HEART_RATE,
        "cad" to SensorType.CADENCE,
        "atemp" to SensorType.TEMPERATURE,
        "power" to SensorType.POWER,
        "PowerInWatts" to SensorType.POWER,
    )

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
    fun read(xml: String): List<GpxTrack> = readDocument(xml).tracks

    /**
     * Parses a GPX document whole: the `creator` attribute, the file-level `<metadata>`, and
     * every `<trk>`.
     *
     * Prefer this over [read] when the file's own metadata matters. Some producers put the
     * information a user would call the activity's name in `<metadata>` and leave the track
     * elements bare, so [read] alone cannot see it.
     *
     * @throws GpxParseException if the XML is malformed or a coordinate is unparseable.
     */
    fun readDocument(xml: String): GpxDocument {
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
        return GpxDocument(
            creator = handler.creator,
            name = handler.metadataName,
            description = handler.metadataDescription,
            time = handler.metadataTime,
            tracks = handler.tracks,
        )
    }

    /**
     * SAX handler mirroring the GPX structure. Text of leaf elements is accumulated in [text]
     * (SAX may deliver character data in several chunks) and consumed on the closing tag.
     */
    private class GpxHandler : DefaultHandler() {
        val tracks = ArrayList<GpxTrack>()
        var creator: String? = null
        var metadataName: String? = null
        var metadataDescription: String? = null
        var metadataTime: Instant? = null

        private var name: String? = null
        private var description: String? = null
        private var type: String? = null
        private var activityId: UUID? = null
        private var points = ArrayList<RawPoint>()
        private var segmentIndex = -1
        private var inTrack = false
        private var inPoint = false
        private var inMetadata = false

        /**
         * Sensor readings found on `<trkpt>` elements, and those found in Trailog's own track-level
         * stream. Kept apart until `</trk>` because a Trailog file carries both — the stream is the
         * truth and the per-point values are a projection of it — and merging them blindly would
         * double every reading. Resolved per type in [takeSamples].
         */
        private var pointSamples = ArrayList<SensorSample>()
        private var streamSamples = ArrayList<SensorSample>()

        /** Sensor values seen on the `<trkpt>` currently open, emitted with its timestamp. */
        private var pointSensors = LinkedHashMap<SensorType, Double>()

        /**
         * The type of the `<trailog:samples>` block currently open, or null when none is open —
         * which also covers a block naming a sensor type this build does not know. An unknown type
         * is skipped rather than rejected, so a file written by a later version still reads.
         */
        private var streamType: SensorType? = null
        private var inSampleStream = false

        /**
         * `<author>` nests its own `<name>` inside `<metadata>`. Without this guard the author's
         * name would overwrite the file's — Sports Tracker's export puts both in exactly that
         * order, so the last one written would win and every file would appear to be named after
         * the account holder.
         */
        private var inAuthor = false

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
                "gpx" -> creator = attributes.getValue("creator")
                    ?: attributes.getValue("", "creator")
                "metadata" -> inMetadata = true
                "author" -> inAuthor = true
                "trk" -> {
                    inTrack = true
                    name = null
                    description = null
                    type = null
                    points = ArrayList()
                    segmentIndex = -1
                    activityId = null
                    pointSamples = ArrayList()
                    streamSamples = ArrayList()
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
                    pointSensors = LinkedHashMap()
                }

                // Trailog's own track-level sensor stream. Only meaningful inside a <trk> and
                // outside a <trkpt>; a same-named element anywhere else is not this.
                "samples" -> if (inTrack && !inPoint) {
                    inSampleStream = true
                    streamType = attr(attributes, "type")?.let { SensorType.byNameOrNull(it) }
                }
                "s" -> if (inSampleStream) {
                    val sensor = streamType
                    // Attributes rather than child elements: a two-hour ride is thousands of
                    // samples, and this keeps the file to one short line each.
                    val at = attr(attributes, "t") ?: fail("<trailog:s> is missing 't'")
                    val raw = attr(attributes, "v") ?: fail("<trailog:s> is missing 'v'")
                    val value = raw.toDoubleOrNull() ?: fail("<trailog:s> has a non-numeric 'v'")
                    // Parsed before the type check so a malformed file fails the same way whether
                    // or not this build happens to know the sensor.
                    if (sensor != null) streamSamples.add(SensorSample(parseTime(at), sensor, value))
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val value = text.toString().trim()
            val element = localOrQ(localName, qName)

            // Sensor readings on a track point, in whatever namespace the producer chose. Matched
            // by local name like everything else here, so gpxtpx:, ns3: and bare all read alike.
            // Checked before the main table because `power` would otherwise need a branch there.
            val sensor = SENSOR_ELEMENT[element]
            if (sensor != null && inPoint) {
                parseDouble(value)?.let { pointSensors[sensor] = it }
                text.setLength(0)
                return
            }

            when (element) {
                // Track-level metadata, only when not inside a point. `<metadata>` sits outside
                // any `<trk>`, so the two cases never collide; `<author><name>` is excluded
                // explicitly because it nests inside `<metadata>`.
                "name" -> when {
                    inTrack && !inPoint -> name = value
                    inMetadata && !inAuthor -> metadataName = value
                }
                "desc" -> when {
                    inTrack && !inPoint -> description = value
                    inMetadata && !inAuthor -> metadataDescription = value
                }
                "type" -> if (inTrack && !inPoint) type = value

                // Trailog's own track identity. Malformed ids are ignored rather than fatal: an
                // unreadable id costs a duplicate on re-import, while refusing the file costs the
                // ride itself.
                "activityId" -> if (inTrack && !inPoint) {
                    activityId = try {
                        UUID.fromString(value)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }

                // Point-level leaves. `speed`/`course` are also core GPX 1.0 fields.
                "ele" -> if (inPoint) ele = parseDouble(value)
                // `<time>` means two different things by position: a fix's timestamp inside a
                // <trkpt>, and the activity's own time inside <metadata>. The latter is the only
                // record of when a track-less activity happened.
                "time" -> when {
                    inPoint -> time = parseTime(value)
                    inMetadata && !inAuthor -> metadataTime = parseTime(value)
                }
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
                    // A per-point reading has no timestamp of its own, so it takes the fix's.
                    for ((kind, reading) in pointSensors) {
                        pointSamples.add(SensorSample(t, kind, reading))
                    }
                    inPoint = false
                }
                "samples" -> if (inSampleStream) {
                    inSampleStream = false
                    streamType = null
                }
                "trk" -> {
                    tracks.add(
                        GpxTrack(
                            name = name,
                            description = description,
                            type = type,
                            points = points,
                            samples = takeSamples(),
                            activityId = activityId,
                        ),
                    )
                    inTrack = false
                }
                "metadata" -> inMetadata = false
                "author" -> inAuthor = false
            }
            text.setLength(0)
        }

        /**
         * The track's sensor stream, resolved per sensor type: where Trailog's own extension
         * carried a type, that is authoritative and the per-point values for it are discarded as
         * the lossy projection they are. Types the extension did not mention fall back to whatever
         * the track points held, which is how a foreign file's heart rate is read.
         *
         * Per type rather than all-or-nothing so a file that mixes the two — Trailog's stream for
         * one sensor, a foreign tool's per-point elements for another — loses neither.
         */
        private fun takeSamples(): List<SensorSample> {
            if (streamSamples.isEmpty()) return SensorStream.normalise(pointSamples)
            val covered = streamSamples.mapTo(HashSet()) { it.type }
            return SensorStream.normalise(
                streamSamples + pointSamples.filter { it.type !in covered },
            )
        }

        /** Prefer the namespace local name; fall back to the raw qName minus any prefix. */
        private fun localOrQ(localName: String?, qName: String?): String =
            if (!localName.isNullOrEmpty()) localName else (qName ?: "").substringAfterLast(':')

        /** An unprefixed attribute, looked up by qName and then by empty-namespace local name. */
        private fun attr(attributes: Attributes, name: String): String? =
            attributes.getValue(name) ?: attributes.getValue("", name)

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
    fun write(
        track: GpxTrack,
        creator: String = DEFAULT_CREATOR,
        time: Instant? = null,
    ): String = write(listOf(track), creator, time)

    /**
     * Serializes tracks to a GPX 1.1 document. Points are grouped into `<trkseg>` by
     * [RawPoint.segmentIndex] in ascending order; within a segment, points are emitted in
     * ascending time so output is deterministic. Non-core fields are written under
     * [TRAILOG_NS]. The result is valid GPX that opens in any third-party tool.
     */
    fun write(
        tracks: List<GpxTrack>,
        creator: String = DEFAULT_CREATOR,
        time: Instant? = null,
    ): String {
        val allTypes = tracks.flatMap { SensorStream.typesIn(it.samples) }.toSet()

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append('\n')
        sb.append("<gpx version=\"1.1\" creator=\"").append(esc(creator)).append('"')
        sb.append(" xmlns=\"").append(GPX_NS).append('"')
        sb.append(" xmlns:trailog=\"").append(TRAILOG_NS).append('"')
        // Garmin's namespaces are declared only when something is actually written in them, so a
        // file with no sensor data looks exactly as it did before sensors existed.
        if (allTypes.any { it != SensorType.POWER }) {
            sb.append(" xmlns:gpxtpx=\"").append(GPXTPX_NS).append('"')
        }
        if (SensorType.POWER in allTypes) {
            sb.append(" xmlns:gpxpx=\"").append(GPXPX_NS).append('"')
        }
        sb.append('>')

        // `<metadata><time>` is when the activity happened. Ordinarily redundant — the first fix
        // says the same thing — but it is the *only* place an activity with no track points can
        // record its own date, and those exist: a workout typed in by hand, or one whose geometry
        // was lost upstream. GPX 1.1 requires <metadata> before any <trk>.
        if (time != null) {
            nl(sb, 1); sb.append("<metadata>")
            nl(sb, 2); sb.append("<time>").append(time.toString()).append("</time>")
            nl(sb, 1); sb.append("</metadata>")
        }

        for (track in tracks) {
            // One sorted list per sensor type, built once per track: the per-point projection
            // below searches it for every fix, and rebuilding it each time would be quadratic.
            val byType = SensorStream.typesIn(track.samples)
                .associateWith { SensorStream.ofType(track.samples, it) }

            nl(sb, 1); sb.append("<trk>")
            if (track.name != null) {
                nl(sb, 2); sb.append("<name>").append(esc(track.name)).append("</name>")
            }
            // GPX 1.1 fixes the order of a <trk>'s children: name, cmt, desc, src, link, number,
            // type, extensions, trkseg. Emitting <desc> anywhere else produces a document that
            // strict validators reject, so this sits between name and type deliberately.
            if (track.description != null) {
                nl(sb, 2); sb.append("<desc>").append(esc(track.description)).append("</desc>")
            }
            if (track.type != null) {
                nl(sb, 2); sb.append("<type>").append(esc(track.type)).append("</type>")
            }
            writeTrackExtensions(sb, track.activityId, byType)
            for (seg in track.points.groupBy { it.segmentIndex }.toSortedMap().values) {
                nl(sb, 2); sb.append("<trkseg>")
                for (p in seg.sortedBy { it.time }) writePoint(sb, p, byType)
                nl(sb, 2); sb.append("</trkseg>")
            }
            nl(sb, 1); sb.append("</trk>")
        }

        nl(sb, 0); sb.append("</gpx>")
        return sb.toString()
    }

    /**
     * The `<trk>`'s own `<extensions>` — everything about the track that GPX itself cannot say.
     * GPX 1.1 places this after `<type>` and before the first `<trkseg>`.
     *
     * Two things live here. The **activity id**, so a file carries its own identity and re-importing
     * it is idempotent rather than duplicating the ride; and the **sensor stream**, exactly as
     * recorded — every sample with its own timestamp, including the ones taken while the GPS had
     * nothing to report, which the per-point projection cannot express at all.
     */
    private fun writeTrackExtensions(
        sb: StringBuilder,
        activityId: UUID?,
        byType: Map<SensorType, List<SensorSample>>,
    ) {
        if (activityId == null && byType.isEmpty()) return
        nl(sb, 2); sb.append("<extensions>")
        if (activityId != null) {
            nl(sb, 3)
            sb.append("<trailog:activityId>").append(activityId.toString())
                .append("</trailog:activityId>")
        }
        for ((type, samples) in byType) {
            nl(sb, 3); sb.append("<trailog:samples type=\"").append(type.name).append("\">")
            for (s in samples) {
                nl(sb, 4)
                sb.append("<trailog:s t=\"").append(s.time.toString())
                    .append("\" v=\"").append(num(s.value)).append("\"/>")
            }
            nl(sb, 3); sb.append("</trailog:samples>")
        }
        nl(sb, 2); sb.append("</extensions>")
    }

    private fun writePoint(
        sb: StringBuilder,
        p: RawPoint,
        byType: Map<SensorType, List<SensorSample>>,
    ) {
        nl(sb, 3)
        sb.append("<trkpt lat=\"").append(num(p.latitude)).append("\" lon=\"").append(num(p.longitude)).append("\">")
        if (p.altitude != null) {
            nl(sb, 4); sb.append("<ele>").append(num(p.altitude)).append("</ele>")
        }
        nl(sb, 4); sb.append("<time>").append(p.time.toString()).append("</time>")

        // The readings nearest this fix in time, within SensorStream.MATCH_TOLERANCE. Lossy by
        // construction — a sample taken 0.4 s after the fix is reported as if simultaneous, and
        // samples with no fix near them are not reported here at all. The authoritative copy is
        // the track-level stream above; this exists so other tools can read the data.
        val nearby = byType.mapNotNull { (type, samples) ->
            SensorStream.nearestTo(samples, p.time)?.let { type to it.value }
        }

        // Trailog extensions — only emitted for fields that are actually present.
        val hasTrailog = p.accuracy != null || p.speed != null || p.bearing != null || p.pressure != null
        if (hasTrailog || nearby.isNotEmpty()) {
            nl(sb, 4); sb.append("<extensions>")
            ext(sb, "accuracy", p.accuracy)
            ext(sb, "speed", p.speed)
            ext(sb, "bearing", p.bearing)
            ext(sb, "pressure", p.pressure)
            writeGarminSensors(sb, nearby)
            nl(sb, 4); sb.append("</extensions>")
        }

        nl(sb, 3); sb.append("</trkpt>")
    }

    /**
     * The nearby readings in Garmin's namespaces: `hr`, `cad` and `atemp` inside a
     * `<gpxtpx:TrackPointExtension>`, and power in its own `<gpxpx:PowerInWatts>` because Garmin
     * never put it in the first schema.
     *
     * Heart rate, cadence and power are written as whole numbers: those elements are integer-typed
     * in Garmin's schema, and `142.0` there is a file strict readers reject. No precision is lost —
     * the sources are integral, and the exact value is in the Trailog stream regardless.
     */
    private fun writeGarminSensors(sb: StringBuilder, readings: List<Pair<SensorType, Double>>) {
        if (readings.isEmpty()) return
        val trackPoint = readings.filter { it.first != SensorType.POWER }
        if (trackPoint.isNotEmpty()) {
            nl(sb, 5); sb.append("<gpxtpx:TrackPointExtension>")
            for ((type, value) in trackPoint) {
                val element = GARMIN_ELEMENT.getValue(type)
                val text = if (type == SensorType.TEMPERATURE) num(value) else rounded(value)
                nl(sb, 6)
                sb.append("<gpxtpx:").append(element).append('>').append(text)
                    .append("</gpxtpx:").append(element).append('>')
            }
            nl(sb, 5); sb.append("</gpxtpx:TrackPointExtension>")
        }
        readings.firstOrNull { it.first == SensorType.POWER }?.let { (_, watts) ->
            nl(sb, 5)
            sb.append("<gpxpx:PowerInWatts>").append(rounded(watts)).append("</gpxpx:PowerInWatts>")
        }
    }

    /** A whole-number rendering for the integer-typed Garmin elements. */
    private fun rounded(value: Double): String = value.roundToLong().toString()

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
