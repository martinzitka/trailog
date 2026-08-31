# Sports Tracker export — DISPOSABLE

> **This directory is throwaway.** It exists to get one person's history out of Sports Tracker
> once. When M2 is finished it gets deleted outright — the module, the entry in
> `settings.gradle.kts`, and the `kotlinx-serialization-json` line in the version catalog that
> nothing else uses. Do not build anything on it, do not import from it, and do not let it grow
> features. The durable half of M2 is `:tools:importer`.

## What it is

A scraper for Sports Tracker's undocumented export endpoints. Sports Tracker offers no bulk
export; the web UI has a per-workout GPX button on the activity editing page and nothing else.

Everything here is **unverified against a real account** and built from public community sources,
some dating to 2016. The host has already changed once, from `www.` to `api.`. Expect breakage
without notice — this is not a maintained feature, and the endpoints owe us nothing.

## Why it is allowed to touch the network at all

`CLAUDE.md` makes adding a network call to a domain the user does not control the single most
important rule in the project, requiring explicit discussion first. That discussion is
`docs/adr/0022-sports-tracker-scraper-egress.md`. In short: this tool ships in no app, runs by
hand, and only ever *pulls the user's own data out of a service they are leaving*. It is the exact
inverse of the third-party egress the rule exists to prevent.

## The token

The tool needs the session token from a logged-in browser.

1. Log in to Sports Tracker in a browser.
2. DevTools → Application → Cookies → `sports-tracker.com` → copy the `sessionkey` value.
3. Put it in a file, or in an environment variable:

On Windows (PowerShell), write the token with an editor rather than by typing it at the prompt —
a pasted command line goes straight into PowerShell history:

```powershell
notepad $env:USERPROFILE\.sports-tracker-token   # paste the token, save, close
$env:SPORTS_TRACKER_TOKEN_FILE = "$env:USERPROFILE\.sports-tracker-token"
```

On a POSIX shell:

```bash
printf '%s' 'PASTE_TOKEN_HERE' > ~/.sports-tracker-token
chmod 600 ~/.sports-tracker-token
export SPORTS_TRACKER_TOKEN_FILE=~/.sports-tracker-token
```

A trailing newline from an editor is fine — the token is trimmed on read.

**Never pass the token as a command-line argument.** Command lines are readable by every process
on the machine and land in shell history. The tool has no flag for it, deliberately.

The token is a live credential for the account. It is never written to disk by this tool, never
logged, and every URI printed for a human goes through `SportsTrackerApi.redact` first.

## Use

Build the launcher once, then run it directly. **Do not use `gradlew run` for this tool.** The
application plugin's `run` task forks the process from the long-lived Gradle daemon, which does not
inherit the invoking shell's environment — so `SPORTS_TRACKER_TOKEN_FILE` can silently fail to
arrive, producing a "no token" error that looks like the variable was never set. `fetch` is also a
long job, and holding a Gradle build open for the length of it is asking for trouble.

```powershell
# Build the launcher (once, and again after any code change).
.\gradlew.bat :tools:sports-tracker-export:installDist

$exe = ".\tools\sports-tracker-export\build\install\sports-tracker-export\bin\sports-tracker-export.bat"

# 1. Settle the FIT question against one real workout. Run this first.
& $exe probe <workoutKey>

# 2. Enumerate the history.
& $exe list

# 3. Download everything. Resumable — re-run it after a failure.
& $exe fetch --format gpx
```

## Running it again later

The migration does not have to happen in one sitting, and the account does not have to be
abandoned before it starts. To pick up workouts recorded since the last run:

```powershell
& $exe list              # re-enumerate; new workouts appear in the index
& $exe fetch --format gpx  # downloads only what is missing
```

`fetch` skips any workout whose file is already on disk *and* sniffs as the right format, and a
skip costs no pause — so a resume run walks the whole index at full speed and stops only where
there is real work. **`list` must be re-run first**, though: `fetch` only ever downloads what the
index names, so without it a second run finds nothing new no matter how many workouts were
recorded upstream.

Each `list` writes a timestamped `workouts-<stamp>.json` beside the latest `workouts.json`. For
the 30 workouts with no track — the manually added swims — that response is the *only* record of
their distance and duration, so overwriting the sole copy would lose data that cannot be
recomputed. It also means a workout deleted upstream survives in an earlier archive.

### Pacing

Downloads pause a random 3–10 seconds by default (`--min-delay` / `--max-delay`). A fixed interval
is both ruder and more conspicuous than a varying one: it draws a metronome in someone else's
access logs. At that spacing a full 1,557-workout run takes roughly two and a half hours, which
costs nothing when the job is resumable and nobody is waiting on it.

The same launcher is generated without the `.bat` extension for POSIX shells.

**JDK 17 is required.** Building under WSL fails with "Cannot find a Java installation ... matching
{languageVersion=17}" unless a JDK 17 is installed there; the Windows side already has one, and
there is no reason to use WSL for this tool.

Output lands in `tools/sports-tracker-export/out/`, which is gitignored: it is the developer's
real location history and must never be committed. Back it up before anything else touches it —
that is M2's first acceptance criterion.

A workout key is the identifier in a workout's own URL in the Sports Tracker web app.

## The answer probe gave (2026-08-31)

**Both endpoints work. FIT is dropped anyway; fetch GPX.**

GPX and FIT both returned HTTP 200 with a genuine body. But the probed workout's GPX has 5,276
track points and zero extension elements, and its FIT body is arithmetically too small to hold
per-point sensor data — 21.1 bytes per record, exactly a no-sensor record, where heart rate alone
would need more bytes than the file contains. The developer confirms the history is plain GPS
tracks. See ADR 0022 and M2.1 in the plan.

So `--format gpx` is the right flag for `fetch`, and `:core` never grew a FIT parser.

## Why `probe` exists

The plan will not commit to a FIT parser on an assumption. A FIT decoder is either a hand-rolled
binary parser or a new `:core` dependency, and `CLAUDE.md` requires asking before adding one.
`probe` fetches the same workout both ways and reports three separate things that are easy to
conflate:

- **Did the request succeed?** HTTP status.
- **Is the body the format we asked for?** Sniffed from the bytes, never from `Content-Type`.
  This API answers an expired session with a 200 and a login page at least as readily as with a
  401, so the status line is hearsay.
- **Is there anything a FIT decoder would be _for_?** A count of per-point heart rate and cadence
  elements in the GPX. Counts only — no coordinate and no reading is ever printed.

What `probe` **cannot** tell you: whether a FIT body contains heart rate the GPX omitted. Proving
that means decoding it, which is the very thing the probe is meant to justify. If FIT downloads
successfully and the GPX turns out to be bare, open the saved `.fit` in a third-party viewer
before anyone writes a parser.

Note that the existing `core/src/test/resources/fixtures/sportstracker_reference.gpx` — a real
export from this account — declares the Garmin `TrackPointExtension` namespace on its root element
but contains **zero** extension elements. That is one workout, not the whole history, but it is
the only evidence in the repo today and it points at GPX being sufficient.

## What is not tested

The network half. There is no fake HTTP server here and there should not be: a test against a
stub would assert that this tool matches *our guess* about an endpoint we have never called, which
is worse than no test because it looks like verification. The pure decision logic — format
sniffing, sensor counting, list parsing, token redaction — is covered, and that is the part that
decides whether a download is real.
