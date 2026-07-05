# TODO

Known gaps worth filling. Ranked by value.

## 0. Same-feed creative fingerprinting (R&D, from the 2026-07-04 ad-discovery research)

Injected creatives repeat across episodes of a feed; show content is unique per episode.
A per-feed fingerprint index (landmark/ChromaPrint-style over decoded PCM) could flag
repeats as ad candidates, catching sticky-fill ads that defeat the same-episode diff —
the blind spot that hit Conan/Nextlander — using only same-tier data. Design constraints
(see docs/ALGORITHM.md "Alternative-detection research"): recurring intros/jingles also
repeat, so never cut a repeat that also appears in a verified-clean copy; ship as a
log-only detector until ear-verified on real feeds. Clean-source discovery (shipped
2026-07-04) covers the currently-failing hosts, so this is not urgent until a host
appears that fills every tier and leaks no clean serving.

## 1. Run tests on non-JVM targets

All tests live in `src/jvmTest`, so the 17 native targets are compile-verified only. The
production logic is 100% common code and the CI shards would run linux/mingw/macos native
tests for free if tests moved to `commonTest`. Blocker: fixture loading — there are no
classpath resources on native. Options: embed the ~40KB of mp3 fixtures as generated code
(base64), or an expect/actual test-path helper reading via okio. The untested surface is
exactly the platform-varying stuff: `atomicMove` semantics, mingw filesystem behavior,
`Dispatchers.IO` on native.

## ~~2. Downloader: check HTTP status + direct tests~~ (done 2026-07-04)

`Downloader.downloadFile` now throws on non-2xx and deletes the partial file when a
download fails mid-stream; `DownloaderTest` covers status handling, mid-download failure,
missing Content-Length progress, parent directory creation, and the pinned user-agent.

## ~~3. Reference promotion onto an existing .adref~~ (done 2026-07-04)

`TacitaImpl` now deletes an existing reference before `atomicMove` promotion (replaces on
all platforms, not just posix), with a covering test in `TacitaTest`.

## ~~4. Id3ChapterShifter unit tests~~ (done 2026-07-04)

`Id3ChapterShifterTest` covers v2.3 vs v2.4 size encoding, the bail-outs
(unsynchronisation, extended header, unsupported versions, non-default frame format
flags), chapters overlapping/inside the cut range, multiple cuts, and real byte offsets.

## ~~5. Mp3SegmentParser edge cases + dead code~~ (done 2026-07-04)

MPEG2/MPEG2.5 frame parsing, the padding bit, and truncated-final-frame handling are now
tested with synthetic frames; the dead `Downloader.fetchString` was deleted.
