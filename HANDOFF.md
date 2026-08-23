# Handoff

State of play as of the first working build. Read `README.md` for architecture
and `CLAUDE.md` for conventions — this file only covers what a fresh session
cannot reconstruct: what was decided, what is actually verified, and what to do
next.

## Where things stand

Branch: `claude/create-project-another-repo-gm1fy0` (two commits).

**This is also the repo's default branch**, because it was the first branch
pushed to an empty repo. You probably want `main` — nothing depends on the
current name:

```bash
git checkout -b main && git push -u origin main
# then set main as default in GitHub repo settings, and delete the long branch
```

## Verified by execution

- `./gradlew assembleDebug` succeeds. Four APKs in `app/build/outputs/apk/debug/`
  (arm64 90 MB, armeabi-v7a 83 MB, x86_64 93 MB, universal 191 MB).
- The arm64 APK was opened and inspected: contains `libpython`, `libffmpeg`,
  `libffprobe`, `libaria2c`; package `app.slurp`; signed v2 with a debug key.
- Library versions confirmed against Maven Central, not guessed:
  youtubedl-android 0.18.1 (`library`, `ffmpeg`, `aria2c`, `common` all exist).

## Not verified — assume broken until proven otherwise

**Nothing has ever run.** No device, no emulator. In particular:

- No link has been probed. No file downloaded.
- `MediaStoreSink` has never written anything.
- `DownloadService` has never started.
- The share-sheet intent path has never fired.
- yt-dlp's actual JSON field names have never been checked against
  `model/Probe.kt`. **This is the most likely first failure.**
- `Ytdlp.updateEngine` and the `UpdateChannel.STABLE` shape compiled, so the
  signatures are right, but the behaviour is untested.

The build passing means the types line up. It says nothing about the yt-dlp
integration, which is where all the risk is.

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
