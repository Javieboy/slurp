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
means **Update engine** in the overflow menu fetches a newer extractor at runtime
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

**`--no-playlist` belongs on the probe, not just the download.** A YouTube link
shared while watching from a playlist or an autoplay Mix carries both `v=` and
`list=`. `download()` has always passed the flag, but that is too late: the
probe is what expands a playlist into jobs, and it ran without it. Measured
against a real shared link — `_type: playlist`, **279 entries**, so 279 queued
jobs draining one at a time through a queue that does not survive the app being
killed.

`UrlSniffer.namesOneVideoInsideAPlaylist` now decides at probe time. A bare
`/playlist?list=…` still expands, which is verified, and so is the 279 case
collapsing to 1.

**`height<=?1080`, not `height<=1080`.** The `?` makes the constraint advisory.
Plain `<=` fails the entire selector when a site reports no height, which is the
normal case for TikTok, Threads and most of X.

**Native libs must be extracted to disk at install time.**
`jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts`. The Python
runtime has to exist as real files on the filesystem — it cannot be read out of
the APK — and legacy packaging is what puts it there, by setting
`android:extractNativeLibs=true` in the merged manifest. Turn it off and you get
a build that installs fine and dies on the first request.

Note that this means the `.so` entries inside the APK *are* DEFLATE-compressed;
`unzip -v` shows `Defl:N` for `libpython.so` and friends, and that is correct.
Uncompressed-in-the-APK is the opposite setting (`useLegacyPackaging = false`),
which loads libraries directly from the APK without ever writing them to disk
and is exactly the configuration that breaks the runtime. Check the flag, not
the compression:

```
aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep extractNativeLibs
```

Do *not* add `android.bundle.enableUncompressedNativeLibs` to
`gradle.properties` to "help". AGP removed it in 8.1 and now fails the build
outright if it is present; its old behaviour is the default anyway.

**Releases are debug-signed, on purpose.** There is no release keystore. When
`keystore.properties` is absent — it is gitignored and normally is — the release
build signs with this machine's debug key, so cutting a release needs no manual
signing step.

The consequence is the one nyaarank documents: Android only accepts an update
signed with the same key as the install, so **releases have to keep coming from
this machine.** Building on another machine means uninstall-then-reinstall for
anyone who already has it. To move to a real key, drop a `keystore.properties`
in the repo root with `storeFile` / `storePassword` / `keyAlias` /
`keyPassword`; that path is already wired, and switching also costs one
uninstall because the key changes.

**Two different things are called "update", and confusing them wastes time.**

| Menu item | What it replaces | Fixes |
|---|---|---|
| **Update engine** | the bundled yt-dlp, inside the existing install | a site that stopped working |
| **Update app** | the APK itself | anything in slurp's own code |

Engine update is the one to reach for when a site breaks — it lands in seconds
and needs no release. App update ships new code and costs an 80 MB download.
Neither can do the other's job, which is why both exist.

**The update check reads `releases.atom`, not the API.** Unauthenticated
`api.github.com` allows 60 requests an hour **per IP**, and an ISP that NATs its
customers shares one address between thousands of them, so a check can return
403 on a phone that has never called GitHub itself. nyaarank measured this
directly — `/rate_limit` reporting 0 of 60 remaining for an address that had
made no requests of its own — and the fix is ported from there. The atom feed is
served by the web host under no such quota.

The per-ABI splits mean the asset name depends on the device, so the derived URL
is HEAD-checked before use and falls back to `app-universal-release.apk`. **Do
not rename the release assets** without changing `AppUpdater.resolveApkUrl`; a
rename degrades to the universal APK rather than handing the installer a 404
page, but only because of that guard.

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

Needs JDK 17+ and an Android SDK with API 36 installed. Output lands in
`app/build/outputs/apk/debug/`:

| APK | Size |
|---|---|
| `app-arm64-v8a-debug.apk` | 90 MB ← install this one |
| `app-armeabi-v7a-debug.apk` | 83 MB |
| `app-x86_64-debug.apk` | 93 MB |
| `app-universal-debug.apk` | 191 MB |

### Do not "update" the toolchain

The versions in `gradle/libs.versions.toml` are a matched set, not a snapshot of
what was newest. Picking the latest of each independently does not build:

- Compose BOM 2026.08 pins Compose 1.12, which **requires AGP 9.1+ and
  compileSdk 37**.
- AGP 9.x requires Gradle 9.5+, and folds Kotlin support in — it *rejects* the
  `org.jetbrains.kotlin.android` plugin outright, which is a real migration.
- `lifecycle` 2.11.0 also demands compileSdk 37.

So the stack is pinned to the last coherent AGP 8 set: **AGP 8.13.2 / Gradle
8.14.3 / compileSdk 36 / Compose BOM 2026.02.01 (Compose 1.10.4, Material3
1.4.0) / lifecycle 2.10.0**. Moving any one of these forward means moving all of
them, and doing the AGP 9 Kotlin-plugin migration at the same time.

Also: `material3` no longer brings the icons in transitively, so
`material-icons-core` is declared explicitly. The BOM pins it at 1.7.8, which is
where Google froze that artifact.

---

## Status

**Compiles.** `./gradlew assembleDebug` produces the four APKs above; the arm64
one was checked and contains the Python runtime, ffmpeg, ffprobe and aria2c, and
reports its package as `app.slurp`.

**Runs, and downloads.** First run on a real phone, 2026-08-26: two Facebook
videos queued from a paste, downloaded, extracted to m4a with the Audio quality
selected, and landed in the gallery — the job cards read "Saved". That single
screenshot exercised almost the whole pipeline at once: engine init and the
Python unpack from the APK, probe, format selection, download, the ffmpeg
post-process, and the MediaStore write.

Two predictions in this file were wrong and are worth recording. The JSON field
names in `model/Probe.kt` were called the most likely first failure; they were
fine. Facebook was expected to need cookies; it did not.

**YouTube failed, and it is a network problem rather than a code one.** The
probe succeeded — the title came back — and the media fetch died with
`unable to download video data: HTTP Error 403: Forbidden`. The phone was on a
VPN. YouTube serves the watch page to anyone but refuses format URLs from exit
IPs it dislikes, which produces exactly that split. The bundled yt-dlp
(2025.11.12) was pulled out of the APK and run against the same flags from a
residential IP: it downloaded fine. `Ytdlp.hintFor` now says so on the card.

Still unexercised: the share-sheet path (both test links were pasted), the
foreground service, cancel and retry, playlists, and both **Update** actions —
which is the one thing the whole architecture rests on.

---

## Scope

For media you have a right to keep — your own uploads, things published for
download, material you have permission to archive. slurp does not touch DRM
and cannot download from subscription streaming services.

---

## License

GPLv3 — see `LICENSE`.

Not a free choice. `youtubedl-android` declares GPL-3.0 in its published POM,
and slurp links it and ships its ffmpeg build inside the APK, so everything
downstream is GPLv3 as well.

One consequence that outlives the licence file: distributing a binary owes the
*corresponding source* for the bundled components, not only for slurp's own
code. Publishing from this repo covers slurp; release notes need to link
upstream for youtubedl-android, yt-dlp, ffmpeg and aria2c.

---

## Ideas

- Remember completed downloads across launches (the queue is in-memory only).
- Auto-check for a yt-dlp update weekly instead of on demand — `Prefs.lastEngineUpdate` already exists for it.
- Show a thumbnail on the job card; the probe already returns the URL.
- Cookie import, for links that need a login. This is the single biggest
  functional gap: private Instagram and most of Facebook will fail without it.
- Subtitle download for YouTube.
