# ChangeLog

### v0.0.5 - Released 7/20/2026

- Internal: **tests now run on native targets** — the test suite moved from `jvmTest`
  to `commonTest`, so CI's linux/mingw/macos shards exercise the platform-varying
  surface (`atomicMove` semantics, mingw filesystem behavior, `Dispatchers.IO` on
  native) instead of compile-verifying it. The mp3 fixtures are embedded into generated
  test code at build time (base64) since kotlin/native test binaries have no classpath
  resources. Only the JLayer/jump3r-dependent tests stay jvm-only (the `Mp3Decoder`
  digest pin — which verifies cross-platform bit-identical PCM — now runs everywhere;
  the JLayer reference comparison split into `Mp3DecoderReferenceTest`). No production
  code or API change

- **Acoustic ad-fingerprint store** (opt-in): `Tacita.downloadPodcast` gains optional
  `acousticFingerprintStore: Path` + `feedId: String` params. When provided (with
  `cutAds`), tacita maintains a store of level-invariant acoustic fingerprints
  (spectral-peak constellations over the decoded audio) that survive the per-episode
  re-encoding and gain normalization Simplecast-class hosts apply — where byte matching
  can never hit. Unlike the byte-layer `fingerprintStore` (kept per feed), one acoustic
  store is designed to be shared globally across feeds: every stored creative carries
  per-feed attributions (`feedId → provenance`), applied diff cuts auto-seed
  `DIFF_PROVEN` fingerprints attributed to the downloading feed, and a verified-clean
  serving revokes matching fingerprints for the observing feed only — never another
  feed's confirmation (docs/ALGORITHM.md "Store scoping"). Matching is **log-only**:
  recurrences are reported to the log callback (with match timing, feeding the owed
  mobile-cost measurement) and emit no `AdBoundaryCandidate`s until real-feed matches
  are ear-verified. Store failures never fail a download
- **`Tacita.confirmAcousticAd(file, acousticFingerprintStore, feedId, startMs, endMs)`**
  (new API): records a human-confirmed ad in the acoustic store as `HUMAN_CONFIRMED`
  for that feed (merging with any existing attributions; never downgraded). Stationary
  audio (silence, held tones) is rejected — its degenerate constellation would match
  unrelated audio. Also new: `Tacita.acousticFingerprints` / `removeAcousticFingerprint`
  (list / revoke across all feeds), and the public `AcousticFingerprintInfo` type

- Internal: **acoustic fingerprinter core** (`AcousticFingerprinter` + `Fft`) — the
  level-invariant matching layer over decoded PCM that the mp3-decoder groundwork existed
  for: Hann STFT (1024/512 at 11.025kHz) → gain-invariant spectral-peak constellation →
  Shazam-style landmark hashes, matched by per-fingerprint time-offset consensus. Pinned
  in tests: a creative fingerprinted from one encode is found in an episode that embeds
  the same audio through a different encoder run (different bitrate, sample rate and
  gain — the Simplecast-class serving shape) and across independently-encoded stitched
  segments, while degenerate audio (silence, held tones) is rejected at extraction. Not
  yet wired into `Tacita.downloadPodcast` — store integration awaits the global-store
  provenance design (docs/ALGORITHM.md). No public API or behavior change. New
  jvmTest-only dependency: jump3r (pure-java LAME port, used as an in-test encoder for
  cross-encode fixtures)

### v0.0.4 - Released 7/19/2026

- Internal: **common-code mp3 decoder** (`Mp3Decoder` + `Mp3Tables`) — a pure-Kotlin port
  of minimp3 (CC0; scalar path, float output, Layer III only) that runs on every KMP
  target, verified bit-identical to the C decoder across MPEG-2 mono and MPEG-1
  joint-stereo streams (`Mp3DecoderTest` pins the decoded-PCM digests). Groundwork for the
  acoustic (level-invariant) ad-fingerprint layer — no public API or behavior change.
  New jvmTest-only dependency: jlayer (independent reference decoder); new fixture:
  `stereo.mp3`; new script: `scripts/port-minimp3-tables.py` (mechanical table extraction)
- **Ad-creative fingerprint store** (opt-in): `Tacita.downloadPodcast` gains an optional
  `fingerprintStore: Path` param. When provided (with `cutAds`), tacita maintains a store of
  known ad-creative fingerprints at that path: every applied diff cut auto-seeds a
  `DIFF_PROVEN` fingerprint of the removed bytes; recurrences of stored creatives still
  present in the output file (e.g. sticky fill that blinded the diff) surface as
  `AdBoundaryCandidate.Source.FINGERPRINT` START/END candidates (matches never cut the
  file — the feature ships log-only per docs/ALGORITHM.md); and fingerprints that match a
  verified-clean serving are pruned automatically. Keep one store per feed. Matching is
  byte-exact (4KB rolling-hash blocks verified by SHA-256), streams the file (mobile-safe),
  and only fingerprints/reports runs ≥ 5s. Store failures never fail a download.
- **`Tacita.confirmAd(file, fingerprintStore, startMs, endMs)`** (new API): records a
  human-confirmed ad — fingerprints the creative at that range of a downloaded file and
  stores it as `HUMAN_CONFIRMED` (the strongest evidence tier; never downgraded). Edge
  imprecision is tolerated: episode-unique bytes at the edges simply never match again while
  the creative interior still does. Plus `Tacita.fingerprints(store)` and
  `Tacita.removeFingerprint(store, id)` for listing/revocation, and the public
  `AdFingerprintInfo` (id, provenance, durationMs, sizeBytes) describing store entries.
  Note: callers implementing `Tacita` themselves must add the new methods/param; exhaustive
  `when`s over `AdBoundaryCandidate.Source` must add `FINGERPRINT`
- Confidence priors for the new source: `FINGERPRINT` candidates rank 0.95 (human-confirmed)
  / 0.85 (diff-proven) — a byte-exact recurrence of an ear-verified ad outranks every
  machine signal (see docs/ALGORITHM.md confidence table)
- Internal: `AdFingerprinter` (block fingerprint extract/match), `AdFingerprintStoreFile`
  (atomic flat-file codec), `RollingHash` (shared by `AdCutter`'s anchor index and block
  matching), `Mp3SegmentParser.secondsAtBytes`/`byteRangeForMs` (streaming time↔byte
  mapping), `AdCutter.Result.AdsCut.fingerprints` (seeding payload)
- CI: snapshot publishes now use Maven's timestamped unique-snapshot protocol (new
  `scripts/upload-snapshots.py` — uploads timestamped filenames and re-PUTs each module's
  `maven-metadata.xml` with an incremented buildNumber). The previous plain PUTs of
  non-unique snapshot filenames only registered on a version's first publish; sonatype
  central accepted but never served the re-uploads, so 0.0.4-SNAPSHOT kept serving its
  July 14 (pre-fingerprint) bytes no matter how many times it was republished

### v0.0.3 - Released 7/12/2026

- **Confidence scores on ad-boundary candidates** (API break): `AdBoundaryCandidate` gains
  a required `confidence: Float` in `0.0..1.0` — an uncalibrated heuristic ranking of how
  strongly the evidence suggests a real ad boundary (applied diff cuts 0.9 > leaked DAI
  slots 0.8 > guard-refused diff ranges 0.65 > segment joins 0.4 > chapter edges 0.3;
  candidates corroborated by another source within 250ms combine as independent evidence
  and rank higher). The 64-candidate cap now keeps the highest-confidence candidates
  instead of the earliest. Ordering is meaningful, absolute values are not, and no value
  licenses auto-skipping (see docs/ALGORITHM.md). Callers constructing candidates (tests)
  must pass `confidence`; construction outside `0.0..1.0` throws

- **Ad-boundary candidates on `Complete`** (API break): `DownloadState.Complete` is now a
  `data class` carrying `adBoundaryCandidates: List<AdBoundaryCandidate>` — an aggressive,
  read-only last-detection pass that surfaces points in the output file that *might* be an
  ad start/end (always empty when `cutAds` is false). Candidates come from four signals:
  stitch/segment joins, the diff (applied splice points *and* ranges the guards refused to
  cut), ad-slot positions leaked in the host's redirect chain, and host-written ID3 CHAP
  frame edges. The pass never modifies the file and never fails the download. Candidates
  are **unverified by design** — render them as skippable chapter markers only; never
  auto-cut or auto-skip on them (see docs/ALGORITHM.md). Callers matching
  `DownloadState.Complete` without `is` (or comparing with `==`) must update; `Complete`
  also now arrives after one extra read+scan of the output file
- `AdCutter.Result` (internal) now carries the frame-snapped cut list on both `AdsCut` and
  `Skipped`; `CleanSourceResolver` (internal) returns leaked DAI slot positions instead of
  only logging them; new internal `Id3FrameReader` extracted from `Id3ChapterShifter`
  (shifting behavior unchanged)
- **Fix: the candidate pass no longer loads the whole file into memory.** The initial
  implementation read the entire output file into one ByteArray; on Android-sized heaps a
  100MB+ episode OOMed *inside the pass's own guards*, silently yielding zero candidates
  (field-observed 2026-07-05 on a 141MB episode; desktop JVMs were unaffected). The
  segment scan now streams through a fixed 1MB window and the ID3 chapter read stops at
  the tag's declared size, so peak memory is independent of episode length. Detection
  results are byte-identical (window-equivalence covered by tests)

### v0.0.2 - Released 7/5/2026

- **Clean-source discovery**: `Tacita.downloadPodcast` gained optional
  `declaredEnclosureBytes` / `expectedDurationSeconds` params (the feed's
  `enclosure length` and `itunes:duration`). When provided with `cutAds`, tacita first
  probes for a serving that provably matches the feed-declared size/duration — the
  pinned-tier serving, a static `fallback_url` leaked in the host's redirect chain
  (Audioboom), or a bot-tier serving (Simplecast) — and downloads that copy directly,
  skipping the diff. Fixes hosts that inject sticky fill on every tier, where an
  immediate same-session reference is blind (see docs/ALGORITHM.md, 2026-07-04 research).
  Note: callers implementing `Tacita` themselves must add the new params.
- `Downloader` internally supports 1-byte Range probes (final URL + Content-Length) and
  per-call user-agent overrides to power the above; the pinned default UA is unchanged.

- Added an optional `log: (String) -> Unit` param to `Tacita.withClient` — receives diagnostic
  log lines (currently one per ad-cut pass, reporting its outcome); defaults to discarding them
- Added `Tacita.withLogger(log)` — returns an instance with the default http-client factory but
  a custom logger
- `Downloader` now throws on non-2xx responses instead of saving the error body as the
  episode file, and deletes the partial file when a download fails mid-stream
- Reference promotion (`overwrite` + `cutAds` over an existing output) now replaces an
  existing stale reference file on all platforms (previously could throw on Windows)
- Removed dead internal `Downloader.fetchString` (RSS fetching lives in consuming apps)
- New test coverage: direct `Downloader` tests (status handling, progress with/without
  Content-Length, parent dir creation, pinned user-agent), `Id3ChapterShifter` unit tests
  (v2.3/v2.4 size encoding, bail-outs, overlapping cuts, real byte offsets), and
  `Mp3SegmentParser` MPEG2/MPEG2.5 + truncated-frame edge cases

### v0.0.1 - Released 7/3/2026

- Initial release: `Tacita.downloadPodcast(url, outputFile, referenceFile, overwrite, cutAds)`
  returns a `Flow<DownloadState>` — downloads a podcast episode (ktor + okio) and losslessly cuts
  dynamically-injected ads by diffing against a second copy of the same episode
- `Tacita` is an interface; its companion object is the default instance and
  `Tacita.withClient(reuse, factory)` returns an instance backed by a custom `HttpClient` factory
  (`reuse = true` shares one never-closed client across downloads)
- Extracted from the podcast-puller-2 project into a standalone Kotlin Multiplatform library
- Targets JVM and all Kotlin/Native platforms supported by ktor + okio (ios, macos, tvos, watchos,
  linux, mingw)

### EOF
