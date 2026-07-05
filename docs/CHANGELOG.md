# ChangeLog

### v0.0.2-SNAPSHOT - Unreleased

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
