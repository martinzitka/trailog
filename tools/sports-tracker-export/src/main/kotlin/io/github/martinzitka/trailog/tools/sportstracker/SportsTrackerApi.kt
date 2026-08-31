package io.github.martinzitka.trailog.tools.sportstracker

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Which export route to ask for. The two differ by exactly one path segment. */
enum class ExportFormat(val pathSegment: String, val extension: String) {
    GPX("exportGpx", "gpx"),
    FIT("exportFit", "fit"),
}

/** One downloaded body, plus what the server claimed about it. */
data class Download(
    val status: Int,
    val contentType: String?,
    val bytes: ByteArray,
) {
    val format: PayloadFormat get() = PayloadSniffer.sniff(bytes)

    /** True only when the body is the format that was asked for — not merely when HTTP said 200. */
    fun isUsable(requested: ExportFormat): Boolean = PayloadSniffer.matches(bytes, requested)

    // ByteArray in a data class: equals/hashCode are identity-based and would be wrong. Nothing
    // compares Downloads, so rather than write structural versions that invite an expensive
    // accidental comparison of megabyte bodies, they are left off deliberately.
}

/**
 * A thin client for Sports Tracker's undocumented export endpoints.
 *
 * **Everything here is unverified.** The routes come from community scripts, some dating to 2016,
 * and the host has already moved once (`www.` to `api.`). Treat every response as hostile: this
 * API answers an expired session with HTTP 200 and a login page at least as readily as with a
 * 401, which is why [Download.isUsable] sniffs bytes rather than trusting the status line.
 *
 * **The token never appears in a log, an error message or a process argument.** It is a live
 * session credential for the user's account. It arrives from the environment (see [Cli]) and
 * every URI that leaves this class for a human's eyes goes through [redact] first.
 */
class SportsTrackerApi(
    private val token: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = defaultClient(),
) {

    /** Fetches the workout list as a raw body. Parsing is [WorkoutIndex]'s problem. */
    fun workoutList(limit: Int): Download =
        get(URI.create("$baseUrl/apiserver/v1/workouts?token=$token&limit=$limit"))

    /**
     * Fetches one workout in one format, retrying a body that is not the requested format.
     *
     * A 200 carrying JSON instead of a track was originally treated as a permanent failure, on the
     * reasoning that a rejected request will be rejected again. Observation says otherwise: during
     * a full run roughly 2% of workouts failed this way at random, and they were ordinary rides
     * with real tracks — a 62 km ride among them — not workouts missing their data. Whatever
     * produces it (rate limiting is the likeliest) is transient, so it is worth another attempt.
     *
     * The cost of being wrong is small and bounded: a genuinely absent workout wastes two extra
     * requests, on a small fraction of the run.
     */
    fun export(workoutKey: String, format: ExportFormat): Download {
        val uri = URI.create(
            "$baseUrl/apiserver/v1/workout/${format.pathSegment}/$workoutKey?token=$token",
        )
        var last = get(uri)
        repeat(UNUSABLE_RETRIES) { attempt ->
            if (last.isUsable(format)) return last
            Thread.sleep(UNUSABLE_BACKOFF_MILLIS * (attempt + 1))
            last = get(uri)
        }
        return last
    }

    /**
     * Issues a GET, retrying transport failures and 5xx responses with a widening pause.
     *
     * 4xx is not retried: a rejected token or a missing workout will be rejected again, and
     * hammering someone else's server to re-learn that is both rude and a good way to get the
     * account rate-limited mid-migration.
     */
    private fun get(uri: URI): Download {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val download = Download(
                    status = response.statusCode(),
                    contentType = response.headers().firstValue("content-type").orElse(null),
                    bytes = response.body(),
                )
                if (response.statusCode() < 500) return download
                lastError = IOException("HTTP ${response.statusCode()} from ${redact(uri)}")
            } catch (e: IOException) {
                lastError = e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1))
        }
        throw IOException("gave up on ${redact(uri)} after $MAX_ATTEMPTS attempts", lastError)
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.sports-tracker.com"

        /**
         * Honest about what this is. A tool scraping an undocumented endpoint should not pretend
         * to be a browser — if the operator wants to block it, they are entitled to.
         */
        private const val USER_AGENT = "trailog-migration/1.0 (one-off personal data export)"

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MILLIS = 2_000L

        /**
         * Extra attempts when the body is the wrong format. Longer backoff than the transport
         * retry above, because the suspected cause is rate limiting and retrying hard would be
         * exactly the wrong response to being asked to slow down.
         */
        private const val UNUSABLE_RETRIES = 2
        private const val UNUSABLE_BACKOFF_MILLIS = 15_000L
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)

        private fun defaultClient(): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // NEVER: the token rides in the query string, and a redirect to another host
                // would hand it over. Redirects are surfaced as a 3xx for a human to look at.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()

        /**
         * Replaces the token's value in a URI so it can be printed.
         *
         * Every path that renders a URI for a human must go through here. The token is a live
         * session credential, and this tool's output is exactly the kind of thing that ends up
         * pasted into an issue.
         */
        fun redact(uri: URI): String =
            uri.toString().replace(Regex("token=[^&]*"), "token=REDACTED")
    }
}
