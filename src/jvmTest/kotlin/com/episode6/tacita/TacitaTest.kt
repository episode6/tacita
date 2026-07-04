package com.episode6.tacita

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    assertEquals(2, requestCount)
    val downloadedFiles = states.filterIsInstance<DownloadState.Downloading>().map { it.file }.distinct()
    assertEquals(listOf(outputFile.toOkioPath(), referenceFile.toOkioPath()), downloadedFiles)
    states.filterIsInstance<DownloadState.Downloading>().forEach {
      assertTrue(it.percentComplete in 0f..1f)
    }
    assertIs<DownloadState.CuttingAds>(states[states.lastIndex - 1])
    assertIs<DownloadState.Complete>(states.last())
    assertContentEquals(contentA + contentB, outputFile.readBytes())
    assertTrue(referenceFile.exists(), "reference should be kept for future runs")
  }

  @Test fun `skips ad cutting when cutAds is false`() = runBlocking<Unit> {
    val bytes = contentA + adA + contentB

    val states = downloadPodcast(responses = listOf(bytes), overwrite = false, cutAds = false).toList()

    assertEquals(1, requestCount)
    assertTrue(states.none { it is DownloadState.CuttingAds })
    assertIs<DownloadState.Complete>(states.last())
    assertContentEquals(bytes, outputFile.readBytes())
    assertFalse(referenceFile.exists())
  }

  @Test fun `promotes the overwritten file to reference instead of downloading one`() = runBlocking<Unit> {
    val previousDownload = contentA + adB + contentB
    outputFile.writeBytes(previousDownload)

    val states = downloadPodcast(
      responses = listOf(contentA + adA + contentB),
      overwrite = true,
      cutAds = true,
    ).toList()

    assertEquals(1, requestCount)
    assertContentEquals(previousDownload, referenceFile.readBytes())
    assertContentEquals(contentA + contentB, outputFile.readBytes())
    assertIs<DownloadState.Complete>(states.last())
  }

  @Test fun `reuses an existing reference file`() = runBlocking<Unit> {
    referenceFile.writeBytes(contentA + adB + contentB)

    downloadPodcast(responses = listOf(contentA + adA + contentB), overwrite = false, cutAds = true).toList()

    assertEquals(1, requestCount)
    assertContentEquals(contentA + contentB, outputFile.readBytes())
  }

  @Test fun `creates a fresh client per download by default`() = runBlocking<Unit> {
    var factoryCalls = 0
    val sharedEngine = engine(listOf(contentA, contentA))
    val tacita = Tacita.withClient { factoryCalls++; HttpClient(sharedEngine) }

    tacita.downloadPodcast(URL, outputFile.toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()
    tacita.downloadPodcast(URL, dir.resolve("two.mp3").toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()

    assertEquals(2, factoryCalls)
  }

  @Test fun `shares a single never-closed client when reuse is true`() = runBlocking<Unit> {
    var factoryCalls = 0
    val sharedEngine = engine(listOf(contentA, contentA))
    val tacita = Tacita.withClient(reuse = true) { factoryCalls++; HttpClient(sharedEngine) }

    tacita.downloadPodcast(URL, outputFile.toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()
    tacita.downloadPodcast(URL, dir.resolve("two.mp3").toOkioPath(), referenceFile.toOkioPath(), overwrite = false, cutAds = false).toList()

    assertEquals(1, factoryCalls)
    // the second download used the same, still-open client
    assertContentEquals(contentA, dir.resolve("two.mp3").readBytes())
  }

  @Test fun `fails when the output file exists and overwrite is false`() {
    outputFile.writeBytes(contentA)

    assertFailsWith<FileAlreadyExistsException> {
      runBlocking {
        downloadPodcast(responses = listOf(contentA), overwrite = false, cutAds = false).toList()
      }
    }
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
