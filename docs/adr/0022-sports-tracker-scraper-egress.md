# 22. The Sports Tracker scraper is the one sanctioned third-party network client

Date: 2026-08-30

## Status

Accepted. Scoped to M2.1 and expires with it. ADR 0015 continues to govern map sources and the
app's own egress; nothing here relaxes that.

## Context

CLAUDE.md is unambiguous: "Adding any network call to a domain the user does not control requires
explicit discussion first. This is the single most important rule in this file." M2.1 requires
calling `api.sports-tracker.com`, a domain the user very much does not control, in order to
retrieve nine years of their own recorded rides. This ADR is that discussion.

The rule exists to stop the user's location history reaching a third party. The scraper runs in the
opposite direction: it takes location history *out* of a third party that already holds it, on the
way to deleting the account. Applying the rule's letter to forbid it would protect nothing and cost
the user their history — Sports Tracker offers no bulk export, so the alternative is clicking a GPX
button several hundred times or abandoning the data.

There is a second, subtler reason to write this down. A future reader grepping for network calls
will find `HttpClient` in this repository and needs to be able to tell in one file whether the
project's central rule was broken or deliberately scoped.

## Decision

**The scraper may call Sports Tracker. Nothing else may call anything.** The permission is narrow
and structural, not a matter of anyone remembering the policy:

**It is a separate Gradle module, not code in `:app`, `:core` or `:server`.** `:app` gains no
dependency on it and cannot invoke it. Nothing that ships to a phone contains this code.

**It does not depend on `:core`.** The scraper moves opaque bytes from an endpoint to the local
disk and never parses a track. Parsing belongs to `:tools:importer` (M2.2), through `:core`'s
single GPX implementation. Keeping the scraper ignorant of the domain model is what lets it be
deleted later without leaving a hole.

**It only ever pulls.** There is no POST, no PUT, and no request body anywhere in it. The tool is
incapable of sending the user's data to anyone; it can only retrieve.

**It is throwaway and dated.** `tools/sports-tracker-export/README.md` opens with a disposal
notice. When M2 completes, the module, its entry in `settings.gradle.kts`, and the
`kotlinx-serialization-json` line in the version catalog that nothing else uses are all deleted.

**Redirects are never followed.** `HttpClient.Redirect.NEVER`. The session token rides in the query
string because that is how the endpoint is built, and a redirect to another host would hand that
token to whoever the redirect named. A 3xx is surfaced for a human to look at.

## The token

A live session credential for the user's account, and treated as one.

**Read from the environment, never from a command line.** `SPORTS_TRACKER_TOKEN`, or
`SPORTS_TRACKER_TOKEN_FILE` naming a file — the file form being better, since a file can carry
restrictive permissions while an environment variable is readable from `/proc/<pid>/environ`.
There is deliberately no `--token` flag: command lines are visible in the process table and land in
shell history.

**Never logged.** Every URI rendered for a human goes through `SportsTrackerApi.redact` first, and
there is a test asserting the token does not survive it. This follows the same reasoning CLAUDE.md
gives for keeping coordinates out of logs: not because logs are assumed hostile, but because logs
get pasted into bug reports.

**Never written to disk by the tool.** If the user parks one in a file, `.gitignore` covers
`*.token` and `sports-tracker-token*`.

## Trusting the response

The endpoints are undocumented, unversioned, and reconstructed from community scripts, some dating
to 2016. The host has already moved once, from `www.` to `api.`.

**Success is decided by sniffing the bytes, never by the status code or `Content-Type`.** This API
answers an expired session with HTTP 200 carrying a login page at least as readily as with a 401.
A tool that trusted the status line would write several hundred HTML files named `.gpx` and report
a clean run. `PayloadSniffer` is the single place that decision is made, so the fresh-download path
and the resume path cannot disagree; the resume path re-sniffs files already on disk, which is what
stops a previously-saved error page from being cemented into the archive.

**4xx is not retried, 5xx is.** A rejected token will be rejected again, and re-learning that by
hammering someone else's server is both rude and a good way to be rate-limited mid-migration.
Downloads are paced 1.5 s apart and the User-Agent says honestly what the tool is.

## The FIT question, settled 2026-08-31

**Answered by the first real request, exactly as intended. FIT is dropped.** No decoder is
written, `:core` gains no dependency, and GPX is the migration format.

Both endpoints turned out to work — HTTP 200 with a genuine body of the requested format each
time, from a route community scripts described in 2016. So the question was never whether FIT was
*available*, only whether it was *worth* anything. Three independent lines of evidence say no:

**The GPX carries nothing.** 5,276 track points, zero extension elements. Sports Tracker's
exporter declares the Garmin `TrackPointExtension` namespace on the root element — it is capable
of writing heart rate — and wrote none.

**The FIT body is too small to hold any.** 111,475 bytes of data across 5,276 records is 21.1
bytes per record, which is precisely a record of timestamp, latitude, longitude, altitude,
distance and speed with no sensor fields. Adding heart rate alone would need 22 bytes per record,
or 116,072 bytes — more than the file actually contains. This is arithmetic on the header's own
declared data size, so it required no decoder, which is what makes it admissible as an answer to
a question about whether to build one.

**The developer confirms it.** The workouts are plain GPS tracks. The exceptions are manually
entered sessions such as a swim, which carry no track at all — only a distance and a duration.

### Corrected 2026-08-31, after the full workout list was fetched

The claim above is *almost* right and was stated too strongly. Of 1,557 workouts, **nine do carry
heart rate**: eight runs from 2013–2016 and one mountain-bike ride from 2020, with genuine avg and
max values between 134 and 162 bpm. Cadence is zero across all 1,557 without exception.

The decision does not change, for reasons that are stronger than the original ones:

- Nine workouts is 0.6% of the history, and a FIT decoder cannot be justified by them.
- Their **avg and max heart rate are already preserved** in the workout-list JSON, which this tool
  saves verbatim. Whatever happens to the per-point data, the summary survives.
- `:core`'s `RawPoint` has no heart-rate field. Preserving *per-point* HR would mean changing the
  domain model, the Room schema and the sync DTOs for nine activities recorded a decade ago.

### Closed on the merits, same day

One of the nine was probed. **GPX carries per-point heart rate whenever there is any to carry**:
1,130 track points, 1,130 `<extensions>` elements, 1,130 heart-rate values. That is why the
exporter declares the Garmin extension namespace on every file — it writes the elements when the
data exists, and this account's other workouts simply have none.

So GPX loses nothing FIT would have kept, and the decision no longer rests on cost-benefit at all.

The size arithmetic above was independently validated in passing: this workout's FIT is 25,792
bytes over 1,130 records, or **22.8 bytes per record**, against 21.1 for the workout with no heart
rate. That is precisely the one-to-two byte per-record difference the table predicted for a
heart-rate field, measured on a different file. The reasoning that justified skipping the decoder
turned out to be sound when tested against a case that should have broken it.

The lesson worth keeping: the single-workout probe answered the format question correctly but
generalised badly about *content*. One sample settled "does the endpoint work"; only the full list
settled "what is in the data".

That last point has a consequence for M2.2: **an empty GPX is a valid case, not a failure.** The
metadata for a manually entered workout exists only in the workout-list response, which is why
`list` writes that JSON to disk verbatim rather than only deriving an index from it.

The original reasoning is kept below, because the *method* is the reusable part — it is how the
next unverified third-party claim should be handled too.

## The original question (superseded by the section above)

The plan holds that FIT would be preferable to GPX — it carries heart rate and cadence natively
where GPX needs extensions — and that an `exportFit` variant of the endpoint is reported by several
independent community sources. Both claims are unverified against a real account.

A FIT decoder is either a hand-rolled binary parser or a new `:core` dependency, which CLAUDE.md
says requires asking first. **That decision is deferred until one real request has been made**, and
the `probe` subcommand exists to make it. It reports three things kept deliberately separate: HTTP
success, whether the body is genuinely the requested format, and how much per-point sensor data the
GPX actually carries — because FIT is only worth decoding if the historical workouts contain
something GPX omits.

What `probe` cannot answer is whether a FIT body holds heart rate the GPX left out; proving that
requires the decoder whose existence is the question. If FIT downloads and the GPX turns out bare,
the saved `.fit` goes into a third-party viewer before anyone writes a parser.

One piece of evidence already exists in the repository and it points at GPX being sufficient:
`core/src/test/resources/fixtures/sportstracker_reference.gpx`, a real export from this account,
declares the Garmin `TrackPointExtension` namespace on its root element and contains zero extension
elements. That is one workout, not the history.

## Consequences

The project's central rule stays intact and now has a written, bounded exception rather than an
undocumented one. A reader who finds HTTP code in the repo can tell in one file why it is there and
when it leaves.

The cost is that the network half of this tool is untestable and untested — see the README. A test
against a stub HTTP server would assert that the scraper matches *our guess* about an endpoint
nobody has called, which is worse than no test because it reads as verification. The pure decision
logic is covered instead, and the first real request is treated as the experiment it is.

If the endpoints have been retired, M2.1 fails and the fallback is the web UI's per-workout GPX
button. That would be tedious, not blocking: `:tools:importer` reads a folder of GPX files
regardless of how they got there.
