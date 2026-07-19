# TODO

Known gaps worth filling. Ranked by value.

## 0. Confirmed-creative fingerprint store (R&D; two-host validation 2026-07-19)

Injected creatives repeat across episodes of a feed; show content is (nearly) unique per
episode. The 2026-07-19 experiments (docs/ALGORITHM.md "Cross-episode creative reuse")
measured both host classes: on Audioboom the repeats are **byte-identical** (81–90% of
two episodes' fill shared, slot-independent, zero collisions with clean content) so
byte-level fingerprints (anchor hashes + strong digest, reusing the cutter's machinery)
get full cross-episode power; on Simplecast the same creative recurs with **different
bytes** (per-episode loudness normalization: same duration + encoded size, PCM
correlation 0.998, gain +1.8dB), so cross-episode matching there needs a level-invariant
acoustic layer (Wang-2003 constellation over decoded PCM → needs a common-code mp3
decoder; minimp3 is CC0). Layered shape: byte-level store first (Audioboom-class
cross-episode + same-episode redownloads everywhere), acoustic layer as the follow-up.
Auto-seed `DIFF_PROVEN` fingerprints from applied diff cuts; consumers submit
`HUMAN_CONFIRMED` ranges (an ear-verified candidate); match content-addressed on future
downloads. Constraints (see the ALGORITHM.md entry): only HUMAN_CONFIRMED + full-digest
matches may ever cut, ship log-only until ear-verified, drop any fingerprint matching a
verified-clean serving, enforce a minimum creative length, support revocation. Open:
Acast player-tier reuse, cross-week recurrence/shelf life, store format + API shape.
Clean-source discovery covers the currently-failing hosts, so still not urgent until a
host fills every tier and leaks no clean serving — but this is now also the only
designed answer to the byte-identical-in-both-copies blind spot.

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
