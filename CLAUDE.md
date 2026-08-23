# slurp

Android app: paste or share a link from YouTube / TikTok / Instagram / Facebook
/ Threads / X, get the file in the gallery. Playlists queue every video.

Read `README.md` first — it carries the architecture, the flags that matter, and
the list of things that will bite you. This file only adds what an agent working
on the repo needs on top of that.

## Status

**Never compiled.** Scaffolded on a machine with no Android SDK. The first job
for anyone picking this up is `./gradlew assembleDebug` and fixing what falls
out — expect Compose Material3 signature drift and the exact shape of the
youtubedl-android callback / `UpdateChannel` API to be the two sore spots.

Do not describe any part of this as working until it has run on a device.

## Shape

`app.slurp` — Kotlin, Compose, Material3. No DI framework, no Room, no
WorkManager. Singletons (`DownloadQueue`, `Ytdlp`) hold what little state there
is, and the queue is in-memory: it does not survive process death. That is a
known gap, not an oversight.

Dependencies are deliberately few: Compose, kotlinx-serialization, and
youtubedl-android. Keep it that way — the APK is already ~180 MB because of the
bundled Python runtime, and every addition is on top of that.

## When a site stops working

Try the **Update** button before touching any code. It fetches a newer yt-dlp at
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
