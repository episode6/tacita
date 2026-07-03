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

**Download an episode (with progress)...**

```kotlin
val downloader = Downloader() // optionally pass your own HttpClient / FileSystem / user-agent

downloader.downloadFile(url = episodeUrl, outputFile = "episode.mp3".toPath(), overwrite = true)
  .collect { progress -> println("${(progress * 100).toInt()}%") }
```

<br/>

**Cut the injected ads out of it...**

Dynamic ad insertion varies per request, while the episode's real content is served as the same
encoded bytes every time. Download the same episode twice and diff the copies: byte runs present in
both are kept, byte runs unique to the primary copy are the injected ads. Cut edges are snapped to
mp3 frame boundaries and ID3 chapter marks are shifted to match.

```kotlin
val adCutter = AdCutter()

val result = adCutter.cutAds(file = "episode.mp3".toPath(), referenceFile = "reference.mp3".toPath())
when (result) {
  is AdCutter.Result.AdsCut     -> println("cut ${result.adBreaksRemoved} ad breaks (${result.secondsRemoved}s)")
  is AdCutter.Result.NoAdsFound -> println("copies are identical, nothing injected")
  is AdCutter.Result.Skipped    -> println("left untouched: ${result.reason}")
}
```

The failure mode is always keeping too much, never cutting material that belongs to the episode: if
the copies don't largely agree (or a safety check fails) the file is left untouched.

<br/>

**Inspect the stitched segments of an mp3...**

```kotlin
val scan = Mp3SegmentParser.scan(bytes)
scan.segments.forEach { println("segment at ${it.startSeconds}s (${it.durationSeconds}s)") }
```

<br/>

**A note on user-agents:** ad servers stitch different fills per client tier keyed on user-agent.
`Downloader` sends an explicit, configurable user-agent so the served bytes (and ad-cut behavior)
stay stable across the two downloads.

{% include readme_index.html %}
