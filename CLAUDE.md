# slurp

Android app: paste or share a link from YouTube / TikTok / Instagram / Facebook
/ Threads / X, get the file in the gallery. Playlists queue every video.

Read `README.md` first — it carries the architecture, the flags that matter, and
the list of things that will bite you. This file only adds what an agent working
on the repo needs on top of that.

## Status

**Runs, and downloads.** First real-device run on 2026-08-26 took two Facebook
videos from paste to files in the gallery, audio-extracted. Engine init, probe,
download, ffmpeg and the MediaStore write are all confirmed working. See
"Status" in `README.md` for what that run did and did not cover.

Still unexercised: the share sheet, the foreground service, cancel/retry,
playlists, queue restore, and both **Update** actions.

Do not describe those as working until something has run them. And note that
the two things this repo predicted would break first — the `Probe.kt` field
names, and Facebook needing cookies — were both wrong. Prefer reproducing a
failure over reasoning about where it probably is.

The counterexample is worth holding alongside that. The persistent queue shipped
in 1.4.0, was released, and could never once have restored a job: `runJob` did
not initialise the engine, and the restore path is the only one that reaches it
without probing first. Reading found it; no amount of using the app on a good
day would have. So: reproduce failures rather than reasoning about them, *and*
read the paths nothing has walked.

Before changing versions in `gradle/libs.versions.toml`, read the "Do not
'update' the toolchain" section of the README. They are a matched set and
bumping one independently breaks the build.

## Shape

`app.slurp` — Kotlin, Compose, Material3. No DI framework, no Room, no
WorkManager. Singletons (`DownloadQueue`, `Ytdlp`) hold what little state there
is. The queue is persisted by `QueueStore` as a plain JSON file — deliberately
not Room, since the whole state is one list — and restored on launch, with
anything caught mid-save stopped rather than retried so a killed process cannot
produce two copies of the same video.

Dependencies are deliberately few: Compose, kotlinx-serialization, and
youtubedl-android. Keep it that way — the APK is already ~180 MB because of the
bundled Python runtime, and every addition is on top of that.

## When a site stops working

Try **Update engine** before touching any code. It fetches a newer yt-dlp at
runtime, and site breakage is almost always an extractor problem that upstream
has already fixed. Reaching for the debugger first wastes an afternoon.

If an update does not fix it, the next most likely cause is authentication:
private Instagram and most of Facebook need cookies, which slurp does not
support yet.

## Working on the parser

`UrlSniffer` exists because share-sheet text is messy — captions wrapped around
links, trailing punctuation, zero-width characters. Any change there should be
checked against real shares from all six apps, not invented strings. That is how
the trailing-bracket case was found.

## Conventions

- Comments explain *why*, especially where a line looks arbitrary but is load
  bearing (`--no-playlist`, `height<=?`, `useLegacyPackaging`). Do not strip
  them; a future reader will otherwise "simplify" one and break the app.
- Everything touching yt-dlp goes through `Ytdlp` and stays on `Dispatchers.IO`.
  It forks a real Python process and blocks the calling thread.
- Errors reaching the UI go through `Ytdlp.describe()`, which digs the useful
  line out of a Python traceback.
