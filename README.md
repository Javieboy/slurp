# slurp

Paste a link, get the file. Android.

Handles YouTube, TikTok, Instagram, Facebook, Threads and X, plus the ~1800
other sites yt-dlp knows. Playlists queue every video in them.

The intended gesture is not pasting at all — it is **Share → slurp** from
inside whichever app you are already in. The link is queued before the share
sheet has finished closing.

---

## How it works

yt-dlp does the extraction. It is bundled inside the APK along with a Python
runtime, via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
0.18.1, so there is no server and nothing to host.

That choice is the whole architecture, and it was made for one reason:
**extractors break constantly.** Sites change their players and yt-dlp ships a
fix within days. The alternatives both fail here — hand-written extractors mean
six things breaking on six independent schedules, and a self-hosted backend
means paying for a box and pushing every byte through it twice. Bundling yt-dlp
means the **Update** button in the top bar fetches a newer extractor at runtime
and fixes a broken site without shipping a new APK. Reach for it first when
something stops working.

| Piece | File |
|---|---|
| Pull a URL out of share-sheet noise | `core/UrlSniffer.kt` |
| Site badge | `core/Site.kt` |
| yt-dlp wrapper — probe, download, update | `engine/Ytdlp.kt` |
| Quality → yt-dlp format selectors | `engine/FormatPolicy.kt` |
| Queue, retries, state | `download/DownloadQueue.kt` |
| Staying alive in the background | `download/DownloadService.kt` |
| Landing files in the gallery | `download/MediaStoreSink.kt` |
| UI | `ui/HomeScreen.kt` |

A download is two steps. First a **probe** (`--dump-single-json
--flat-playlist`) asks yt-dlp what the link actually is; a playlist expands into
one job per video right there. Then each job downloads into its own empty
directory and whatever file appears is copied into `Movies/slurp` (or
`Music/slurp`) through MediaStore.

The empty-directory trick is deliberate. Reconstructing yt-dlp's output path in
Kotlin is guesswork — extensions change after a merge, titles get sanitised
differently per platform, and `--extract-audio` renames the file afterwards.
Looking in an empty directory is exact.

---

## Things that will bite you

**Downloads run one at a time.** Not a simplification. Every one of these sites
rate-limits hard, and three parallel Instagram downloads earn a temporary block
that looks, from inside the app, exactly like a broken extractor.

**`--no-playlist` on every single download.** A YouTube link copied while
watching a playlist carries both `v=` and `list=`. Without the flag, one queued
video quietly becomes two hundred.

**`height<=?1080`, not `height<=1080`.** The `?` makes the constraint advisory.
Plain `<=` fails the entire selector when a site reports no height, which is the
normal case for TikTok, Threads and most of X.

**Native libs must stay uncompressed.** `useLegacyPackaging = true` in
`app/build.gradle.kts` and `android.bundle.enableUncompressedNativeLibs=false`
in `gradle.properties`. The Python runtime is unpacked from the APK at first
launch and cannot be read from a compressed entry. Change either and you get a
build that installs fine and dies on the first request.

**Minification is off for release.** The library reaches into bundled Python by
name, R8 cannot see those references, and a minified build fails at runtime
rather than at compile time.

**The APK is large.** ~180 MB universal, roughly a third of that per ABI split,
because a Python runtime ships per architecture. Install the `arm64-v8a` split
on any phone from the last several years.

**minSdk is 29.** MediaStore's `RELATIVE_PATH` and `IS_PENDING` are API 29.
Supporting 26–28 means a second legacy write path plus `WRITE_EXTERNAL_STORAGE`.

**No `ACTION_VIEW` intent filter.** Adding one would make slurp a candidate
every time a link is tapped anywhere on the phone. Share is the explicit
gesture; keep it that way.

---

## Building

```
./gradlew assembleDebug
```

Needs JDK 17+ and an Android SDK with API 36. Output splits land in
`app/build/outputs/apk/debug/`.

Not yet built or run on a device — see **Status**.

---

## Status

Written, not yet compiled. There was no Android SDK on the machine this was
scaffolded on, so nothing here has seen a compiler.

Expect the first `./gradlew assembleDebug` to surface import and API-signature
mistakes, most likely around Compose Material3 (which churns between releases)
and the exact shape of the youtubedl-android callback and `UpdateChannel` API.
The architecture and the yt-dlp flags are the parts worth trusting; the Kotlin
needs a compiler pass.

Nothing has been verified against a real link on a real device.

---

## Scope

For media you have a right to keep — your own uploads, things published for
download, material you have permission to archive. slurp does not touch DRM
and cannot download from subscription streaming services.

---

## Ideas

- Remember completed downloads across launches (the queue is in-memory only).
- Auto-check for a yt-dlp update weekly instead of on demand — `Prefs.lastEngineUpdate` already exists for it.
- Show a thumbnail on the job card; the probe already returns the URL.
- Cookie import, for links that need a login. This is the single biggest
  functional gap: private Instagram and most of Facebook will fail without it.
- Subtitle download for YouTube.
