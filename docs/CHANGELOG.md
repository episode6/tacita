# ChangeLog

### v1.0.0-SNAPSHOT - Unreleased

- Initial release: `Tacita.downloadPodcast(url, outputFile, referenceFile, overwrite, cutAds)`
  returns a `Flow<DownloadState>` — downloads a podcast episode (ktor + okio) and losslessly cuts
  dynamically-injected ads by diffing against a second copy of the same episode
- Extracted from the podcast-puller-2 project into a standalone Kotlin Multiplatform library
- Targets JVM and all Kotlin/Native platforms supported by ktor + okio (ios, macos, tvos, watchos,
  linux, mingw)

### EOF
