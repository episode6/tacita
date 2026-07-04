> Jupiter cut out her tongue for talking too much — she became the goddess of silence.

[![Maven Central](https://img.shields.io/maven-central/v/com.episode6.tacita/tacita.svg?style=flat-square)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.episode6.tacita%22)

Tacita downloads podcast episodes and cuts dynamically-injected ads out of them — losslessly, with
no re-encoding. It's a Kotlin Multiplatform library targeting JVM and all Kotlin/Native platforms
supported by its dependencies (ios, macos, tvos, watchos, linux and mingw).

### Installation

```groovy
dependencies {
  implementation("com.episode6.tacita:tacita:{{ site.version }}")
}
```

<sub>{{ site.title }} v{{ site.version }} is compiled against Kotlin v{{ site.kotlinVersion }}</sub>

### Usage

Tacita has a single entry point:

```kotlin
Tacita.downloadPodcast(
  url = episodeUrl,
  outputFile = "episode.mp3".toPath(),
  referenceFile = "episode.mp3.adref".toPath(),
  overwrite = true,
  cutAds = true,
).collect { state ->
  when (state) {
    is DownloadState.Downloading -> println("${state.file.name}: ${(state.percentComplete * 100).toInt()}%")
    is DownloadState.CuttingAds  -> println("cutting ads...")
    is DownloadState.Complete    -> println("done!")
  }
}
```

Dynamic ad insertion varies per request, while the episode's real content is served as the same
encoded bytes every time. Tacita downloads the episode, downloads a second copy to
`referenceFile`, and diffs them: byte runs present in both are kept, byte runs unique to the
primary copy are the injected ads. Cut edges are snapped to mp3 frame boundaries (no re-encoding)
and ID3 chapter marks are shifted to match. The failure mode is always keeping too much, never
cutting material that belongs to the episode — if the copies don't largely agree (or a safety
check fails) the file is left untouched.

Back-to-back requests tend to receive identical fill, so a copy from an earlier session makes a
far better reference than a fresh one: when `overwrite`-ing an existing `outputFile` it is
promoted to become the reference, an existing `referenceFile` is reused instead of re-downloaded,
and reference files are kept on disk for future runs.

<br/>

**A note on http engines:** the JVM artifact ships with ktor's okhttp engine included. On other
platforms, add any [ktor client engine](https://ktor.io/docs/client-engines.html) to your build
and it will be picked up automatically.

<br/>

**Custom http clients:** the `Tacita` companion object is itself a `Tacita` instance backed by
ktor's default engine discovery. For custom engine config — or tests using ktor's `MockEngine` —
hold your own instance instead (e.g. as a DI singleton):

```kotlin
val tacita: Tacita = Tacita.withClient { HttpClient(myEngine) }
```

The factory is invoked once per download and must return a new client each time; tacita owns and
closes it when the download completes.

{% include readme_index.html %}
