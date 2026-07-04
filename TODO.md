# TODO

Known gaps worth filling, from the 2026-07-03 test-coverage review. Ranked by value.
(Items 2 and 3 are correctness fixes disguised as test gaps.)

## 1. Run tests on non-JVM targets

All tests live in `src/jvmTest`, so the 17 native targets are compile-verified only. The
production logic is 100% common code and the CI shards would run linux/mingw/macos native
tests for free if tests moved to `commonTest`. Blocker: fixture loading — there are no
classpath resources on native. Options: embed the ~40KB of mp3 fixtures as generated code
(base64), or an expect/actual test-path helper reading via okio. The untested surface is
exactly the platform-varying stuff: `atomicMove` semantics, mingw filesystem behavior,
`Dispatchers.IO` on native.

## 2. Downloader: check HTTP status + direct tests

`Downloader.downloadFile` never checks the response status — a 404 streams the error page
to disk and reports success (dead episode URLs are a real-world case). Fix (throw on
non-2xx), plus direct tests for: mid-download failure (currently leaves a partial file —
decide if intended), missing Content-Length (progress emits only 0f/1f), and parent
directory creation. Note: changing downloader behavior triggers
`.agents/update-algorithm-doc-skill/`.

## 3. Reference promotion onto an existing .adref

`TacitaImpl` promotes the output file with `fileSystem.atomicMove(outputFile,
referenceFile)`. If a stale reference already exists (re-download with overwrite twice),
the move replaces on Linux/JVM but can throw on Windows. Add an explicit
`delete(referenceFile, mustExist = false)` before the move, plus a test.

## 4. Id3ChapterShifter unit tests

Only exercised through one happy-path AdCutter test. Untested: v2.3 vs v2.4 size encoding,
the bail-outs (unsynchronisation flag, extended header, non-default frame format flags),
chapters overlapping the cut range, and real byte offsets (the existing test only uses the
0xFFFFFFFF "no offset" sentinel).

## 5. Mp3SegmentParser edge cases + dead code

All fixtures are MPEG1/44.1kHz — MPEG2/2.5 frame parsing and truncated-final-frame
handling are untested. Also: `Downloader.fetchString` is dead code (the RSS fetch moved to
the consuming app) — delete it rather than test it.
