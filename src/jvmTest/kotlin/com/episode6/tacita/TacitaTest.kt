package com.episode6.tacita

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import com.episode6.tacita.audio.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test

class TacitaTest {

  private val contentA = fixture("content-a.mp3") // ~6.1s
  private val contentB = fixture("content-b.mp3") // ~8.1s
  private val adA = fixture("ad-a.mp3") // ~2.1s
  private val adB = fixture("ad-b.mp3") // ~3.1s

  private val dir = Files.createTempDirectory("tacita-test").toFile().apply { deleteOnExit() }
  private val outputFile = dir.resolve("episode.mp3")
  private val referenceFile = dir.resolve("episode.mp3.adref")

  private var requestCount = 0

  @Test fun `downloads both copies, reports progress and cuts ads`() = runBlocking<Unit> {
    val states = downloadPodcast(
      responses = listOf(contentA + adA + contentB, contentA + adB + contentB),
      overwrite = false,
      cutAds = true,
    ).toList()

    assertThat(requestCount).isEqualTo(2)
    val downloadedFiles = states.filterIsInstance<DownloadState.Downloading>().map { it.file }.distinct()
    assertThat(downloadedFiles).containsExactly(outputFile.toOkioPath(), referenceFile.toOkioPath())
    states.filterIsInstance<DownloadState.Downloading>().forEach {
      assertThat(it.percentComplete).isBetween(0f, 1f)
    }
    assertThat(states[states.lastIndex - 1]).isInstanceOf(DownloadState.CuttingAds::class)
    assertThat(states.last()).isInstanceOf(DownloadState.Complete::class)
    assertThat(outputFile.readBytes()).isEqualTo(contentA + contentB)
    assertThat(referenceFile.exists(), name = "reference should be kept for future runs").isTrue()
  }

  @Test fun `skips ad cutting when cutAds is false`() = runBlocking<Unit> {
    val bytes = contentA + adA + contentB

    val states = downloadPodcast(responses = listOf(bytes), overwrite = false, cutAds = false).toList()

    assertThat(requestCount).isEqualTo(1)
    assertThat(states.filterIsInstance<DownloadState.CuttingAds>()).isEmpty()
    assertThat(states.last()).isInstanceOf(DownloadState.Complete::class)
    assertThat(outputFile.readBytes()).isEqualTo(bytes)
    assertThat(referenceFile.exists()).isFalse()
  }

  @Test fun `promotes the overwritten file to reference instead of downloading one`() = runBlocking<Unit> {
    val previousDownload = contentA + adB + contentB
    outputFile.writeBytes(previousDownload)

    val states = downloadPodcast(
      responses = listOf(contentA + adA + contentB),
      overwrite = true,
      cutAds = true,
    ).toList()

    assertThat(requestCount).isEqualTo(1)
    assertThat(referenceFile.readBytes()).isEqualTo(previousDownload)
    assertThat(outputFile.readBytes()).isEqualTo(contentA + contentB)
    assertThat(states.last()).isInstanceOf(DownloadState.Complete::class)
  }

  @Test fun `promotes over a stale reference left by an earlier overwrite`() = runBlocking<Unit> {
    val previousDownload = contentA + adB + contentB
    outputFile.writeBytes(previousDownload)
    referenceFile.writeBytes(contentA + adA + contentB) // stale: already consumed by an earlier run

    val states = downloadPodcast(
      responses = listOf(contentA + adA + contentB),
      overwrite = true,
      cutAds = true,
    ).toList()

    assertThat(requestCount).isEqualTo(1)
    assertThat(referenceFile.readBytes(), name = "promotion should replace the stale reference").isEqualTo(previousDownload)
    assertThat(outputFile.readBytes()).isEqualTo(contentA + contentB)
    assertThat(states.last()).isInstanceOf(DownloadState.Complete::class)
  }

  @Test fun `reuses an existing reference file`() = runBlocking<Unit> {
    referenceFile.writeBytes(contentA + adB + contentB)

    downloadPodcast(responses = listOf(contentA + adA + contentB), overwrite = false, cutAds = true).toList()

    assertThat(requestCount).isEqualTo(1)
    assertThat(outputFile.readBytes()).isEqualTo(contentA + contentB)
  }

  @Test fun `creates a fresh client per download by default`() = runBlocking<Unit> {
    var factoryCalls = 0
    val sharedEngine = engine(listOf(contentA, contentA))
    val tacita = Tacita.withClient { factoryCalls++; HttpClient(sharedEngine) }

    tacita.downloadPodcast(URL, outputFile.toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()
    tacita.downloadPodcast(URL, dir.resolve("two.mp3").toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()

    assertThat(factoryCalls).isEqualTo(2)
  }

  @Test fun `shares a single never-closed client when reuse is true`() = runBlocking<Unit> {
    var factoryCalls = 0
    val sharedEngine = engine(listOf(contentA, contentA))
    val tacita = Tacita.withClient(reuse = true) { factoryCalls++; HttpClient(sharedEngine) }

    tacita.downloadPodcast(URL, outputFile.toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()
    tacita.downloadPodcast(URL, dir.resolve("two.mp3").toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()

    assertThat(factoryCalls).isEqualTo(1)
    // the second download used the same, still-open client
    assertThat(dir.resolve("two.mp3").readBytes()).isEqualTo(contentA)
  }

  @Test fun `reports ad-cut outcome to the log param`() = runBlocking<Unit> {
    val logLines = mutableListOf<String>()
    val engine = engine(listOf(contentA + adA + contentB, contentA + adB + contentB))
    val tacita = Tacita.withClient(log = { logLines += it }) { HttpClient(engine) }

    tacita.downloadPodcast(URL, outputFile.toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = true).toList()

    assertThat(logLines).hasSize(1)
    assertThat(logLines.single()).startsWith("AdCutter: episode.mp3: AdsCut")
  }

  @Test fun `serves a verified clean copy directly and skips the diff`() = runBlocking<Unit> {
    val clean = contentA + contentB
    val logLines = mutableListOf<String>()
    val engine = engine(listOf(clean, clean)) // one probe, one download
    val tacita = Tacita.withClient(log = { logLines += it }) { HttpClient(engine) }

    val states = tacita.downloadPodcast(
      url = URL,
      outputFile = outputFile.toOkioPath(),
      referenceFile = referenceFile.toOkioPath(),
      overwrite = false,
      cutAds = true,
      declaredEnclosureBytes = clean.size.toLong(),
    ).toList()

    assertThat(requestCount).isEqualTo(2)
    assertThat(states.filterIsInstance<DownloadState.CuttingAds>(), name = "clean copies need no diff").isEmpty()
    assertThat(states.last()).isInstanceOf(DownloadState.Complete::class)
    assertThat(outputFile.readBytes()).isEqualTo(clean)
    assertThat(referenceFile.exists(), name = "no reference needed for a clean serving").isFalse()
    assertThat(logLines.filter { "clean serving downloaded directly" in it }).hasSize(1)
  }

  @Test fun `falls back to the diff pipeline when no serving matches the feed's declared size`() = runBlocking<Unit> {
    // adA (4310B) must exceed the resolver's 4096B tolerance floor or the filled copy would validate
    val filledA = contentA + adA + contentB
    val filledB = contentA + adB + contentB
    // pinned probe + 2 bot-UA probes all see filled copies, then the normal double download
    val engine = engine(listOf(filledA, filledA, filledA, filledA, filledB))
    val tacita = Tacita.withClient { HttpClient(engine) }

    val states = tacita.downloadPodcast(
      url = URL,
      outputFile = outputFile.toOkioPath(),
      referenceFile = referenceFile.toOkioPath(),
      overwrite = false,
      cutAds = true,
      declaredEnclosureBytes = (contentA + contentB).size.toLong(),
    ).toList()

    assertThat(requestCount).isEqualTo(5)
    assertThat(states.filterIsInstance<DownloadState.CuttingAds>()).hasSize(1)
    assertThat(outputFile.readBytes(), name = "diff pipeline should still cut the ads").isEqualTo(contentA + contentB)
  }

  @Test fun `a truncated clean download is discarded and the diff pipeline runs instead`() = runBlocking<Unit> {
    val clean = contentA + contentB
    val filledA = contentA + adA + contentB
    val filledB = contentA + adB + contentB
    var request = 0
    val engine = MockEngine {
      request++
      when (request) {
        // probe validates against the declared size…
        1    -> respond(ByteArray(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, clean.size.toString()))
        // …but the "clean" download comes up short (connection dropped mid-body)
        2    -> respond(clean.copyOf(clean.size / 2), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, clean.size.toString()))
        3    -> respond(filledA, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, filledA.size.toString()))
        else -> respond(filledB, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, filledB.size.toString()))
      }
    }
    val tacita = Tacita.withClient { HttpClient(engine) }

    val states = tacita.downloadPodcast(
      url = URL,
      outputFile = outputFile.toOkioPath(),
      referenceFile = referenceFile.toOkioPath(),
      overwrite = false,
      cutAds = true,
      declaredEnclosureBytes = clean.size.toLong(),
    ).toList()

    assertThat(request).isEqualTo(4)
    assertThat(states.filterIsInstance<DownloadState.CuttingAds>()).hasSize(1)
    assertThat(outputFile.readBytes(), name = "truncated copy must never be served").isEqualTo(contentA + contentB)
  }

  @Test fun `fails when the output file exists and overwrite is false`() {
    outputFile.writeBytes(contentA)

    assertFailure {
      runBlocking {
        downloadPodcast(responses = listOf(contentA), overwrite = false, cutAds = false).toList()
      }
    }.isInstanceOf(FileAlreadyExistsException::class)
  }

  private fun engine(responses: List<ByteArray>): MockEngine = MockEngine {
    val body = responses[requestCount++]
    respond(
      content = body,
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentLength, body.size.toString()),
    )
  }

  private fun downloadPodcast(responses: List<ByteArray>, overwrite: Boolean, cutAds: Boolean): Flow<DownloadState> {
    val engine = engine(responses)
    return Tacita.withClient { HttpClient(engine) }.downloadPodcast(
      url = URL,
      outputFile = outputFile.toOkioPath(),
      referenceFile = referenceFile.toOkioPath(),
      overwrite = overwrite,
      cutAds = cutAds,
    )
  }
}

private const val URL = "https://example.com/episode.mp3"
