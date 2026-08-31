package io.github.martinzitka.trailog.tools.sportstracker

import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * M2.1 — the throwaway Sports Tracker export tool. See README.md beside this source tree.
 *
 * Three subcommands, meant to be run in this order:
 *
 * - `probe <workoutKey>` — fetch one workout as **both** GPX and FIT and report what actually
 *   came back. This exists to settle the question the plan says must be settled before a FIT
 *   decoder is worth building, and it is the first thing to run against a fresh token.
 * - `list` — fetch the workout list, save it verbatim, and derive a TSV index.
 * - `fetch` — download every workout named in the index. Resumable and self-healing.
 *
 * **The token comes from the environment, never from a command line.** Command lines are visible
 * to every other process on the machine via the process table, and land in shell history. See
 * [readToken].
 */
private const val TOKEN_ENV = "SPORTS_TRACKER_TOKEN"
private const val TOKEN_FILE_ENV = "SPORTS_TRACKER_TOKEN_FILE"

private const val DEFAULT_OUT = "tools/sports-tracker-export/out"

/**
 * Pause between downloads in `fetch`, drawn uniformly at random from this range.
 *
 * Someone else's server, and a migration that will run for hours. A fixed interval is both
 * ruder and more conspicuous than a varying one — it produces a metronome in their access logs
 * that looks exactly like what it is. Randomising costs nothing here because the job is
 * resumable and nobody is waiting on it.
 */
private const val DEFAULT_MIN_DELAY_MILLIS = 3_000L
private const val DEFAULT_MAX_DELAY_MILLIS = 10_000L

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(1)
    }
    val command = args.first()
    val rest = args.drop(1)

    try {
        when (command) {
            "probe" -> exitProcess(probe(rest))
            "list" -> exitProcess(list(rest))
            "fetch" -> exitProcess(fetch(rest))
            "verify" -> exitProcess(verify(rest))
            "--help", "-h", "help" -> {
                usage()
                exitProcess(0)
            }
            else -> {
                System.err.println("unknown command: $command")
                usage()
                exitProcess(1)
            }
        }
    } catch (e: IllegalStateException) {
        // Configuration problems (no token, no index) are the user's to fix and do not deserve
        // a stack trace.
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

// ---- commands ---------------------------------------------------------------------------

/**
 * Fetches one workout both ways and reports what each endpoint actually returned.
 *
 * The report deliberately separates three questions that are easy to conflate: did the request
 * succeed (HTTP), is the body the format we asked for (sniffed, not header-trusted), and does the
 * data carry anything a FIT decoder would be *for* (sensor scan). A 200 that returns a login page
 * looks like success on the first question and fails the second.
 */
private fun probe(args: List<String>): Int {
    val opts = Options(args)
    val key = opts.positional.firstOrNull()
        ?: error("usage: probe <workoutKey> [--out <dir>]")
    val out = File(opts.value("--out") ?: DEFAULT_OUT, "probe").apply { mkdirs() }
    val api = SportsTrackerApi(readToken())

    println("Probing workout $key")
    println("Output: ${out.absolutePath}")
    println()

    val results = ExportFormat.entries.associateWith { format ->
        val download = api.export(key, format)
        val file = File(out, "$key.${format.extension}")
        file.writeBytes(download.bytes)
        report(format, download, file)
        download
    }

    println()
    println("-- Verdict " + "-".repeat(58))
    val gpx = results[ExportFormat.GPX]
    val fit = results[ExportFormat.FIT]
    val gpxOk = gpx?.isUsable(ExportFormat.GPX) == true
    val fitOk = fit?.isUsable(ExportFormat.FIT) == true

    when {
        !gpxOk && !fitOk -> println(
            "Neither endpoint returned a usable export. The token is the usual cause -\n" +
                "check it is current, then look at the saved bodies by hand."
        )
        gpxOk && !fitOk -> println(
            "GPX works, FIT does not. The plan's fallback applies: GPX alone is enough,\n" +
                "and FIT can leave the plan entirely. No FIT decoder, no new :core dependency."
        )
        fitOk -> {
            println("FIT works. Whether it is worth using is a separate question:")
            val scan = gpx?.takeIf { gpxOk }
                ?.let { GpxSensorScan.of(String(it.bytes, Charsets.UTF_8)) }
            if (scan != null && !scan.hasAnySensorData) {
                println("  - the GPX for this workout carries NO heart rate or cadence.")
                println("  - so unless the FIT holds sensor data the GPX omits, FIT buys nothing.")
                println("  - confirm by opening the saved .fit in a third-party viewer. Proving")
                println("    it from here would mean writing the decoder this probe is meant to")
                println("    justify.")
            } else if (scan != null) {
                println("  - the GPX already carries sensor data, so GPX may well be sufficient.")
            }
        }
    }
    println("Saved bodies are in ${out.absolutePath} - inspect them before deciding.")
    return if (gpxOk || fitOk) 0 else 2
}

/** Fetches the workout list, saves it verbatim, and derives the index that drives `fetch`. */
private fun list(args: List<String>): Int {
    val opts = Options(args)
    val out = File(opts.value("--out") ?: DEFAULT_OUT).apply { mkdirs() }
    val limit = opts.value("--limit")?.toIntOrNull() ?: 10_000
    val api = SportsTrackerApi(readToken())

    val download = api.workoutList(limit)
    // Verbatim first, always. Everything below is a guess about an undocumented shape; the raw
    // response is the thing that is actually true, and re-fetching it costs a request against
    // someone else's rate limit.
    val rawFile = File(out, "workouts.json")
    // Archive every run alongside the latest. `list` is expected to be re-run as new workouts are
    // recorded, and for the 30 manually-added workouts this response is the *only* record of their
    // distance and duration — there is no track to recompute from. Overwriting the sole copy of
    // irreplaceable data to save 6 MB would be a poor trade, and a workout deleted upstream would
    // otherwise vanish from our archive on the next run.
    val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    File(out, "workouts-$stamp.json").writeBytes(download.bytes)
    rawFile.writeBytes(download.bytes)
    println(
        "Raw response: ${rawFile.absolutePath} (${download.bytes.size} bytes, " +
            "HTTP ${download.status}, sniffed ${download.format})"
    )
    println("Archived this run as workouts-$stamp.json")

    if (download.format != PayloadFormat.JSON) {
        System.err.println(
            "The list endpoint did not return JSON. Sniffed ${download.format} - an expired " +
                "token usually shows up as HTML here. Look at the saved file."
        )
        return 2
    }

    val refs = WorkoutIndex.parse(String(download.bytes, Charsets.UTF_8))
    if (refs.isEmpty()) {
        System.err.println(
            "Parsed the response as JSON but found no workouts in it. The response shape has " +
                "probably changed; the raw file is saved and WorkoutIndex is a few lines to fix."
        )
        return 2
    }

    val indexFile = File(out, "index.tsv")
    indexFile.writeText(WorkoutIndex.toTsv(refs))
    println("Indexed ${refs.size} workouts -> ${indexFile.absolutePath}")
    return 0
}

/**
 * Downloads every workout in the index.
 *
 * Resumable and self-healing: a workout whose file is already on disk *and sniffs as the right
 * format* is skipped, while one that was previously saved as an error page is re-fetched. That
 * distinction matters because the failure this API produces most often is a 200 carrying a login
 * page, and a plain exists() check would cement those into the archive permanently.
 */
private fun fetch(args: List<String>): Int {
    val opts = Options(args)
    val out = File(opts.value("--out") ?: DEFAULT_OUT)
    val minDelay = opts.value("--min-delay")?.toLongOrNull() ?: DEFAULT_MIN_DELAY_MILLIS
    val maxDelay = opts.value("--max-delay")?.toLongOrNull() ?: DEFAULT_MAX_DELAY_MILLIS
    if (minDelay < 0) error("--min-delay cannot be negative")
    if (maxDelay < minDelay) error("--max-delay ($maxDelay) is below --min-delay ($minDelay)")
    val formats = when (opts.value("--format")?.lowercase()) {
        null, "gpx" -> listOf(ExportFormat.GPX)
        "fit" -> listOf(ExportFormat.FIT)
        "both" -> listOf(ExportFormat.GPX, ExportFormat.FIT)
        else -> error("--format must be gpx, fit or both")
    }

    val indexFile = File(opts.value("--index") ?: File(out, "index.tsv").path)
    if (!indexFile.isFile) error("no index at ${indexFile.absolutePath} - run `list` first")
    val keys = WorkoutIndex.keysFromTsv(indexFile.readText())
    if (keys.isEmpty()) error("index at ${indexFile.absolutePath} names no workouts")

    val api = SportsTrackerApi(readToken())
    val failures = mutableListOf<String>()
    var downloaded = 0
    var skipped = 0

    println("Fetching ${keys.size} workouts as ${formats.joinToString("/") { it.name }}")
    println("Pausing ${minDelay}-${maxDelay} ms between downloads. Already-present files are")
    println("skipped without a pause, so resuming is fast.")

    keys.forEachIndexed { i, key ->
        formats.forEach { format ->
            val dir = File(out, format.extension).apply { mkdirs() }
            val file = File(dir, "$key.${format.extension}")
            if (file.isFile && file.length() > 0 &&
                PayloadSniffer.matches(file.readBytes(), format)
            ) {
                skipped++
                return@forEach
            }
            val progress = "[${i + 1}/${keys.size}]"
            try {
                val download = api.export(key, format)
                if (download.isUsable(format)) {
                    file.writeBytes(download.bytes)
                    downloaded++
                    println("$progress $key.${format.extension} - ${download.bytes.size} bytes")
                } else {
                    // The archive keeps nothing unusable — a stray error page there would later be
                    // fed to the importer as though it were a ride. But the body is written to a
                    // separate failures directory, because "HTTP 200, sniffed JSON" says a request
                    // failed without saying why, and the answer is in the bytes that were thrown
                    // away. Diagnosing this after a two-hour run should not require repeating it.
                    val bodyFile = File(out, "failures").apply { mkdirs() }
                        .resolve("$key.${format.extension}.body")
                    bodyFile.writeBytes(download.bytes)
                    val snippet = String(download.bytes, Charsets.UTF_8)
                        .take(200)
                        .replace(Regex("\\s+"), " ")
                    failures += "$key ${format.extension} HTTP ${download.status} " +
                        "${download.format} $snippet"
                    System.err.println(
                        "$progress $key.${format.extension} - FAILED " +
                            "(HTTP ${download.status}, body sniffed ${download.format}) $snippet"
                    )
                }
            } catch (e: Exception) {
                failures += "$key ${format.extension} ${e.message}"
                System.err.println("$progress $key.${format.extension} - FAILED (${e.message})")
            }
            // Only after an actual request. A skipped file cost the server nothing, so a resume
            // run walks the whole index at full speed and stops only where work remains.
            Thread.sleep(Random.nextLong(minDelay, maxDelay + 1))
        }
    }

    println()
    println("Downloaded $downloaded, skipped $skipped already present, ${failures.size} failed.")
    if (failures.isNotEmpty()) {
        val failureFile = File(out, "failures.txt")
        failureFile.writeText(failures.joinToString("\n", postfix = "\n"))
        println("Failures listed in ${failureFile.absolutePath}. Re-running retries only those.")
        return 2
    }
    return 0
}

/**
 * Audits the downloaded archive against the index. Reads only local files — no network, no token,
 * safe to run as often as you like.
 *
 * Separate from `fetch` on purpose. Downloading and verifying are different questions, and
 * conflating them is what let four ruined exports through: `fetch` checked that each body was
 * GPX, which it was, and had no opinion about whether a 49 km ride arriving as one track point
 * was plausible. An audit that can be re-run over an archive already on disk also means the
 * answer does not depend on having watched a two-hour download scroll past.
 */
private fun verify(args: List<String>): Int {
    val opts = Options(args)
    val out = File(opts.value("--out") ?: DEFAULT_OUT)
    // The raw response, not index.tsv: it carries `isManuallyAdded`, which is the field that
    // separates a hand-entered workout with no track from a recorded one that lost its track. The
    // index is a thin driver for `fetch` and was never meant to answer this.
    val rawFile = File(opts.value("--workouts") ?: File(out, "workouts.json").path)
    if (!rawFile.isFile) error("no workout list at ${rawFile.absolutePath} — run `list` first")

    val refs = WorkoutIndex.parse(rawFile.readText())
    if (refs.isEmpty()) error("parsed no workouts out of ${rawFile.absolutePath}")

    val gpxDir = File(out, ExportFormat.GPX.extension)
    val audits = refs.map { ref ->
        val distance = ref.distanceRaw?.toDoubleOrNull()
        val file = File(gpxDir, "${ref.key}.${ExportFormat.GPX.extension}")
        val points = if (file.isFile) PayloadSniffer.countTrackPoints(file.readBytes()) else null
        WorkoutAudit(
            key = ref.key,
            expectedDistance = distance,
            trackPoints = points,
            verdict = ArchiveAudit.classify(ref.manuallyAdded, distance, points),
        )
    }

    val report = ArchiveAudit.report(audits)
    println(report)
    val reportFile = File(out, "verify-report.txt")
    reportFile.writeText(report)
    println("Written to ${reportFile.absolutePath}")

    return if (audits.any { it.isProblem }) 2 else 0
}

// ---- plumbing ---------------------------------------------------------------------------

private fun report(format: ExportFormat, download: Download, file: File) {
    val sniffed = download.format
    println("-- ${format.name} " + "-".repeat(62 - format.name.length))
    println("  HTTP status   ${download.status}")
    println("  Content-Type  ${download.contentType ?: "(none)"}")
    println("  Body size     ${download.bytes.size} bytes")
    println("  Sniffed as    $sniffed")
    println("  Usable        ${if (download.isUsable(format)) "yes" else "NO"}")
    println("  Saved to      ${file.name}")

    when {
        sniffed == PayloadFormat.GPX -> {
            val scan = GpxSensorScan.of(String(download.bytes, Charsets.UTF_8))
            println("  Track points  ${scan.trackPoints}")
            println("  Extensions    ${scan.extensionElements}")
            println("  Heart rate    ${scan.heartRate}")
            println("  Cadence       ${scan.cadence}")
            println("  Temperature   ${scan.temperature}")
            println("  Power         ${scan.power}")
        }
        sniffed == PayloadFormat.FIT ->
            println("  FIT data size ${PayloadSniffer.fitDataSize(download.bytes)} bytes")
        // An error body is small and worth showing; it usually says exactly what is wrong.
        download.bytes.size <= 400 ->
            println("  Body          ${String(download.bytes, Charsets.UTF_8).replace('\n', ' ')}")
    }
}

/**
 * Reads the session token from the environment.
 *
 * Two accepted sources, both off the command line: [TOKEN_ENV] directly, or [TOKEN_FILE_ENV]
 * naming a file to read it from. The file form is the better one — an environment variable is
 * still readable from `/proc/<pid>/environ` by the same user, whereas a file can at least carry
 * restrictive permissions.
 *
 * The token is a live session credential for the user's Sports Tracker account. It is never
 * written to disk by this tool, never logged, and never placed in a URL that gets printed.
 */
private fun readToken(): String {
    System.getenv(TOKEN_FILE_ENV)?.let { path ->
        val file = File(path)
        if (!file.isFile) error("$TOKEN_FILE_ENV points at ${file.absolutePath}, which is not a file")
        return file.readText().trim().ifEmpty { error("token file ${file.absolutePath} is empty") }
    }
    System.getenv(TOKEN_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    error(
        "no token. Set $TOKEN_ENV, or $TOKEN_FILE_ENV to a file containing it.\n" +
            "Get it from a logged-in browser: DevTools -> Application -> Cookies ->\n" +
            "sports-tracker.com -> the `sessionkey` cookie's value.\n" +
            "Do not pass it as an argument - command lines are world-readable in the process table."
    )
}

/** The smallest possible flag parser. A throwaway tool does not need a CLI library. */
private class Options(args: List<String>) {
    private val flags = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                flags[arg] = args.getOrNull(i + 1) ?: error("$arg needs a value")
                i += 2
            } else {
                positional += arg
                i++
            }
        }
    }

    fun value(name: String): String? = flags[name]
}

private fun usage() {
    println(
        """
        Sports Tracker export (M2.1, throwaway)

          probe <workoutKey> [--out <dir>]
              Fetch one workout as both GPX and FIT and report what came back.
              Run this first: it settles whether FIT is worth supporting at all.

          list [--limit <n>] [--out <dir>]
              Fetch the workout list, save it verbatim, derive index.tsv.

          fetch [--out <dir>] [--index <file>] [--format gpx|fit|both]
                [--min-delay <ms>] [--max-delay <ms>]
              Download every workout in the index. Resumable; re-running retries
              only what is missing or was saved as an error page, and skips cost
              no pause. Delay defaults to a random 3000-10000 ms per request.

          verify [--out <dir>] [--index <file>]
              Audit the downloaded archive against the index: missing files,
              empty tracks, and exports far too sparse for the distance they
              claim. Local only — no network, no token.

        To pick up workouts recorded after an earlier run, re-run `list` first —
        `fetch` only ever downloads what the index names.

        The session token is read from the $TOKEN_ENV environment variable, or from
        the file named by $TOKEN_FILE_ENV. Never pass it as an argument.
        """.trimIndent()
    )
}
