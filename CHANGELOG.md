# ChangeLog

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
