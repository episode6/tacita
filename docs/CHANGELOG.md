# ChangeLog

### v0.0.3-SNAPSHOT - Unreleased

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
