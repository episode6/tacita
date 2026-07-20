# Agent Guidelines for tacita

## Validating the Project

Run checks and docs generation:

```bash
./gradlew check dokkaGenerateHtml
```

This command:
- Compiles the project
- Runs all tests
- Generates Dokka HTML documentation

A passing build requires exit code 0 with no test failures.

Only host-buildable targets compile locally (e.g. Apple targets are skipped on Linux). CI shards
the full target set across OSes with `-Pfilter=linuxX64|windowsX64|macos`.

## Project Structure

This is a single-module Kotlin Multiplatform project rooted at the top of the repo:

- `src/commonMain/` — all production logic:
  - `Tacita.kt` — the only public entry point: the `Tacita` interface with
    `downloadPodcast(...): Flow<DownloadState>`. Its companion object is the default instance
    (private `TacitaImpl`); `Tacita.withClient(reuse, log, factory)` returns an instance with a
    custom http-client factory. reuse=false (default): factory returns a NEW client per download
    and tacita closes it; reuse=true: factory invoked lazily once, client shared and NEVER
    closed. log (default no-op) receives diagnostic lines (ad-cut outcomes, clean-source
    resolution, ad-boundary detection).
    `Tacita.withLogger(log)` returns an instance with the default client factory + custom logger.
    Also public: the `DownloadState` sealed class (whose terminal `Complete` carries
    `List<AdBoundaryCandidate>`), `AdBoundaryCandidate` and `FileAlreadyExistsException`.
    Everything below is `internal`
  - `http/Downloader.kt` — episode downloads via ktor + okio, progress as a `Flow<Float>`
  - `http/CleanSourceResolver.kt` — probes for a provably-clean serving; also reports leaked
    DAI slot positions
  - `audio/AdCutter.kt` — lossless removal of dynamically-injected ads by diffing two copies
  - `audio/AdBoundaryDetector.kt` — read-only aggressive pass gathering `AdBoundaryCandidate`s
    from the final output file (never modifies it, never fails the download)
  - `audio/Mp3SegmentParser.kt` — splits an mp3 stream into its independently-encoded segments
  - `audio/Id3ChapterShifter.kt` — shifts ID3 CHAP frames to account for cut ranges
  - `audio/Id3FrameReader.kt` — read-only ID3v2 frame/CHAP walker shared by the shifter and
    the boundary detector
- `src/jvmMain/` + `src/nativeMain/` — a single internal expect/actual (`systemFileSystem`),
  needed because okio has no common declaration of `FileSystem.SYSTEM`
- `src/commonTest/` — the test suite; runs on jvm and every host-runnable native target.
  Real mp3 fixtures live in `src/commonTest/resources/audio/` and are embedded into
  generated test code (base64) by the `generateTestFixtures` gradle task, since native
  test binaries have no classpath resources. `TestFiles.kt` holds okio-based temp-file
  helpers (plus a `systemTempDirectory` expect/actual in `jvmTest`/`nativeTest`)
- `src/jvmTest/` — only what needs java-only codec libraries: the JLayer reference
  comparison for `Mp3Decoder` and the acoustic tests that cross-encode fixtures through
  jump3r at test time

## Build Configuration

- **Kotlin**: 2.4.0 with explicit API mode — all public declarations require `public` modifier
- **Targets**: JVM + every Kotlin/Native target the deps support (ios, macos, tvos, watchos,
  linux, mingw — see `Config.KMPTargets` in `buildSrc`); no js/wasm (commonMain relies on
  `Dispatchers.IO` and `FileSystem.SYSTEM`, which don't exist there)
- **JVM target**: 17
- **Gradle**: 9.5.1
- **Docs**: Dokka 2.x

## Key Constraints

- **Logic stays in commonMain**: platform source sets exist only for the `systemFileSystem`
  expect/actual and the `ktor-client-okhttp` engine dependency in `jvmMain`. Don't add
  platform-specific logic. Native-portability gotchas already handled: `Dispatchers.IO` needs
  `import kotlinx.coroutines.IO`, and closing okio types in common code needs `import okio.use`.
- **Single public entry point**: only `Tacita`, `DownloadState` and `FileAlreadyExistsException`
  are public; keep new functionality behind `Tacita` and keep implementation types `internal`
  (tests can still reach them — test compilations associate with main)
- **Explicit API**: Every public function, class, interface, and typealias must have an explicit
  `public` modifier
- **User-agent is deliberate**: the default UA string pins the ad-serving tier; don't change it
- **assertk**: Use `assertFailure { }` not `assertThat { }.isFailure()` (API changed in 0.28.x)
- **Gradle 9**: `useJUnitPlatform()` test modules must declare
  `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` explicitly (handled in
  `buildSrc` ConfigMultiPlugin)

## Algorithm knowledge

`docs/ALGORITHM.md` is the research & validation history behind the ad-cutting design
(serving model, disproven approaches, invariants, validation playbook). Read it before
changing AdCutter/Downloader behavior, and update it alongside any process change or
exploration — see `.agents/update-algorithm-doc-skill/`.

## Skills

See `.agents/` for available skills:
- `.agents/release-branch-skill/` — cut/create a new release branch (e.g. "cut a release branch"); also registered under `.claude/skills/` for auto-trigger
- `.agents/ship-release-skill/` — ship a release
- `.agents/update-algorithm-doc-skill/` — docs/ALGORITHM.md must be updated with any exploration or change to the download/ad-cut process
