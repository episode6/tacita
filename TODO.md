# TODO

Known gaps worth filling. Ranked by value.

## 0. Confirmed-creative fingerprint store (byte layer SHIPPED 2026-07-19; follow-ups open)

The 2026-07-19 experiments (docs/ALGORITHM.md "Cross-episode creative reuse") measured
both host classes: Audioboom serves repeats **byte-identically** (81–90% of two episodes'
fill shared, slot-independent, zero collisions with clean content); Simplecast re-encodes
per episode (same creative, PCM correlation 0.998, gain +1.8dB, zero shared bytes). The
byte-level layer shipped the same day: `downloadPodcast(fingerprintStore=…)` with
`DIFF_PROVEN` auto-seeding, `Tacita.confirmAd` for `HUMAN_CONFIRMED` creatives,
candidates-only matching (`Source.FINGERPRINT`), clean-serving pruning, revocation.
Remaining:

- **Graduate matches from candidates to cuts** — needs real-feed ear verification of
  matched spans first (playbook step 5); then HUMAN_CONFIRMED + full-digest matches
  could cut (the recorded cutting-authority constraint).
- ~~Acoustic layer for Simplecast-class hosts~~ (shipped 2026-07-19 in three steps:
  `Mp3Decoder` minimp3 port, `AcousticFingerprinter` FFT + constellation core, and the
  global acoustic store — `downloadPodcast(acousticFingerprintStore, feedId)` with
  per-feed attributions, DIFF_PROVEN seeding, `confirmAcousticAd`, feed-scoped
  clean-serving revocation; see docs/ALGORITHM.md "Shipped: the global acoustic store").
  Matching is **log-only** pending real-feed ear verification (playbook step 5) — that
  verification, plus the mobile cost measurement (timing now in the log lines) and the
  per-feed negative-evidence design (suppressing a clean-verified feed's future
  candidates without touching other feeds), are what's left before acoustic matches can
  emit candidates with a confidence prior.
- **Field measurements still owed**: Acast player-tier reuse, cross-week creative
  recurrence (fingerprint shelf life), acoustic match-pass cost on mobile.

## 1. Follow-ups from the 2026-07-25 nine-feed sweep (docs/ALGORITHM.md)

- ~~Close the bitrate-validation aliasing hole~~ (done 2026-07-25: the resolver records
  which standard rate the duration check matched and tacita verifies the downloaded
  file's frames actually carry it — else the copy is deleted and the diff pipeline
  decides).
- **Fix candidate-cap starvation**: `MAX_CANDIDATES` truncation is confidence-then-
  earliest, so segment-heavy episodes (Bombcast 255, Rest Is History 493) lose every
  candidate in the back of the file. Stratify the cap over time (or per source).
- **Ear checks owed**: Conan's falsely-clean serving and SYSK's uncut Omny slots
  (first SEGMENT_BOUNDARY validation against real injected fill).

## ~~2. Run tests on non-JVM targets~~ (done 2026-07-19)

The suite moved to `commonTest` (fixtures embedded as base64 via the
`generateTestFixtures` gradle task; okio-based temp-file helpers; `runTest` instead of
`runBlocking`). Still jvm-only: the JLayer reference comparison (`Mp3DecoderReferenceTest`)
and the jump3r-encoding acoustic tests (`AcousticFingerprinterTest`, `TacitaAcousticTest`)
— both depend on java-only codec libraries. Moving those needs pre-generated cross-encode
fixtures checked in (or a native LAME port), which hasn't been worth it.

## ~~3. Downloader: check HTTP status + direct tests~~ (done 2026-07-04)

`Downloader.downloadFile` now throws on non-2xx and deletes the partial file when a
download fails mid-stream; `DownloaderTest` covers status handling, mid-download failure,
missing Content-Length progress, parent directory creation, and the pinned user-agent.

## ~~4. Reference promotion onto an existing .adref~~ (done 2026-07-04)

`TacitaImpl` now deletes an existing reference before `atomicMove` promotion (replaces on
all platforms, not just posix), with a covering test in `TacitaTest`.

## ~~5. Id3ChapterShifter unit tests~~ (done 2026-07-04)

`Id3ChapterShifterTest` covers v2.3 vs v2.4 size encoding, the bail-outs
(unsynchronisation, extended header, unsupported versions, non-default frame format
flags), chapters overlapping/inside the cut range, multiple cuts, and real byte offsets.

## ~~6. Mp3SegmentParser edge cases + dead code~~ (done 2026-07-04)

MPEG2/MPEG2.5 frame parsing, the padding bit, and truncated-final-frame handling are now
tested with synthetic frames; the dead `Downloader.fetchString` was deleted.
