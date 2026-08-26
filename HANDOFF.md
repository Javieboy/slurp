# Handoff

State of play as of the first working build. Read `README.md` for architecture
and `CLAUDE.md` for conventions — this file only covers what a fresh session
cannot reconstruct: what was decided, what is actually verified, and what to do
next.

## Where things stand

Branch: `claude/create-project-another-repo-gm1fy0`.

**This is also the repo's default branch**, because it was the first branch
pushed to an empty repo. You probably want `main` — nothing depends on the
current name:

```bash
git checkout -b main && git push -u origin main
# then set main as default in GitHub repo settings, and delete the long branch
```

## Concurrency fixes on top of that build

Three bugs found by reading the code after the first build. All three are in the
path a first run takes, and all three would present as something else, which is
why they were worth fixing before the device test rather than after.

- **The pump was a check-then-act race.** `startPump()` read and wrote `pump`
  from several `Dispatchers.Default` threads with no lock. Two probes finishing
  together — the normal case for a multi-link paste — both started a pump, and
  two parallel downloads earn the rate-limit block that the one-at-a-time rule
  exists to prevent. The mirror case stranded jobs at "Queued" forever. Both are
  now closed by `pumpLock`: starting takes it, and the pump only retires while
  holding it and only after a final re-check of the queue.
- **Cancelling during "Checking" did not stick.** The in-flight probe replaced
  its own placeholder by id when it landed, so a cancelled link downloaded
  anyway. The swap now happens inside a single `_jobs.update` that drops the
  result if the placeholder went `CANCELLED` or disappeared. `Ytdlp.probe` also
  takes a process id now, so the forked Python process is actually killable.
- **The foreground service was started from the background.** It was started in
  the pump, i.e. after the probe, by which point the user has usually left for
  the app they shared from. On Android 12+ that is
  `ForegroundServiceStartNotAllowedException`, raised inside a coroutine with no
  handler — an app-wide crash, not a failed download. It now starts in
  `submit()`/`retry()` on the caller's thread while the activity is visible, and
  the service stops *itself* when the queue empties instead of being stopped by
  the pump's `finally` (which raced the next submit starting it again).

These compile — `./gradlew assembleDebug` is green with them in, on Windows with
JDK 17 and SDK platform 36. Still never run: the races they close are exactly
the kind of thing a compiler cannot check, so treat them as untested until a
device says otherwise.

## Verified by execution

- `./gradlew assembleDebug` succeeds. Four APKs in `app/build/outputs/apk/debug/`
  (arm64 90 MB, armeabi-v7a 83 MB, x86_64 93 MB, universal 191 MB).
- The arm64 APK was opened and inspected: contains `libpython`, `libffmpeg`,
  `libffprobe`, `libaria2c`; package `app.slurp`; signed v2 with a debug key.
- Library versions confirmed against Maven Central, not guessed:
  youtubedl-android 0.18.1 (`library`, `ffmpeg`, `aria2c`, `common` all exist).

Confirmed on a real phone, 2026-08-26, from one screenshot of the job list:

- Engine init works. The Python runtime unpacks from the APK and yt-dlp runs.
- Probe works, on Facebook and on YouTube — the YouTube card carried a real
  video title before it failed further down.
- Download, `--extract-audio` and the ffmpeg post-process work.
- `MediaStoreSink` works. Two Facebook videos reached the gallery, cards
  reading "Saved".
- `JobCard` renders the right control per state: Retry on the failed job,
  Delete on the finished ones.

## Not verified — assume broken until proven otherwise

- **The share-sheet path.** Both confirmed downloads were pasted, not shared.
  This is the app's whole intended gesture and nothing has exercised it.
- **`DownloadService`.** No evidence either way; the downloads were short and
  the app was in the foreground.
- **The Update button.** `updateYoutubeDL` has never successfully run, and the
  bundled yt-dlp is 2025.11.12 — over nine months old. The entire architecture
  rests on this working. Test it early.
- **Cancel and retry.** Including the CHECKING-cancel fix above.
- **Playlists.** No multi-entry probe has ever been expanded.
- The concurrency fixes. Races are not something a compiler or a single manual
  run can confirm.

## Two predictions this repo got wrong

Worth keeping, as a check on confident reasoning about untested code:

- `model/Probe.kt`'s JSON field names were called "the most likely first
  failure". They were fine on both sites tested.
- Facebook was expected to need cookie import. It downloaded without.

The one real failure so far was not in the code at all — see below.

## The YouTube 403

`unable to download video data: HTTP Error 403: Forbidden`, on a link whose
probe had already succeeded. The phone was on a VPN.

Diagnosed by pulling the bundled yt-dlp out of the APK — it lives at
`res/raw/ytdlp` as a Python zipapp, not in `assets/` — reading its version
(2025.11.12), fetching that exact release, and running it with slurp's own
flags from a residential IP. It downloaded fine, video and audio-only both. So
neither the bundled version nor the format selectors are at fault: YouTube
serves the watch page to anyone and refuses format URLs from VPN exit IPs.

`Ytdlp.hintFor` now turns that error into advice on the card. If a 403 shows up
again with no VPN in play, *then* suspect the stale extractor and hit Update.

## Decisions already made — don't relitigate without a reason

- **Bundled yt-dlp, not a backend and not hand-written extractors.** Extractors
  break constantly; upstream fixes them in days. The in-app **Update** button
  fetches a newer yt-dlp at runtime, which is the entire point.
- **Downloads run one at a time.** Not laziness — parallel requests to these
  sites earn rate-limit blocks that look exactly like broken extractors.
- **minSdk 29.** MediaStore `RELATIVE_PATH`/`IS_PENDING` are API 29; supporting
  26–28 needs a whole legacy write path plus `WRITE_EXTERNAL_STORAGE`.
- **No `ACTION_VIEW` intent filter.** It would make slurp a candidate every time
  any link is tapped anywhere on the phone. Share is the explicit gesture.
- **Release minification off.** The library reaches into bundled Python by name;
  R8 cannot see it, so a minified build fails at runtime, not compile time.
- **The toolchain versions are a matched set.** See "Do not 'update' the
  toolchain" in the README. Newest-of-each does not build: Compose 1.12 needs
  AGP 9.1+ and compileSdk 37; AGP 9 needs Gradle 9.5+ and rejects the
  `kotlin.android` plugin outright, because it provides Kotlin itself. That
  migration was deliberately deferred.

## Do this next, in this order

1. **Build and install locally.** `./gradlew assembleDebug`, then
   `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
   Building locally signs with your own debug key, so later rebuilds install
   over the top instead of forcing an uninstall.
2. **Grant the notification permission.** Denied, downloads run but look dead.
3. **Test in this order** — it isolates failures:
   a plain YouTube video → a TikTok share → an Instagram link → a playlist.
4. **When something fails, read the job card.** The text is yt-dlp's own error
   line, pulled out of the Python traceback by `Ytdlp.describe()`. Fix
   `model/Probe.kt` field names first if the failure is in probing.

## Known gaps, roughly in value order

- **Cookie import.** Private Instagram and most of Facebook will fail without
  it. Biggest functional hole.
- **The queue is in-memory.** It does not survive process death. A killed app
  loses everything queued.
- No download history — finished jobs vanish on restart.
- No thumbnails on job cards, though the probe already returns the URLs.
- Auto-update yt-dlp on a schedule; `Prefs.lastEngineUpdate` exists unused.
- No subtitle support.

## Environment note

Some of these hosts may be DNS-blocked on Indonesian ISPs, the way nyaa.si is
for nyaarank. Nothing here works around that — no DoH fallback was built,
because it should be confirmed on a real device before being solved. If a link
fails with a DNS or connection error rather than a yt-dlp extractor error, that
is the cause, and nyaarank's `MainActivity.java` already has a working DoH +
SNI + SAN-verification implementation worth copying.
