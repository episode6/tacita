package com.episode6.tacita.http

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.messageContains
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.IOException
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test

class DownloaderTest {

  private val dir = Files.createTempDirectory("downloader-test").toFile().apply { deleteOnExit() }
  private val outputFile = dir.resolve("file.bin")
  private val payload = ByteArray(50_000) { it.toByte() } // several read chunks worth

  @Test fun `throws on non-2xx status and leaves no file behind`() {
    val engine = MockEngine { respond("gone", HttpStatusCode.NotFound) }

    assertFailure { download(engine) }
      .isInstanceOf(IOException::class)
      .messageContains("404")

    assertThat(outputFile.exists(), name = "error body saved as a download").isFalse()
  }

  @Test fun `mid-download failure deletes the partial file`() {
    val engine = MockEngine {
      val channel = ByteChannel(autoFlush = true)
      channel.writeFully(payload, 0, 1_000)
      channel.close(IOException("connection reset"))
      respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
    }

    assertFailure { download(engine) }.isInstanceOf(IOException::class)

    assertThat(outputFile.exists(), name = "partial download left on disk").isFalse()
  }

  @Test fun `reports granular progress when content-length is known`() {
    val engine = MockEngine {
      respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
    }

    val progress = download(engine)

    assertThat(progress.first()).isEqualTo(0f)
    assertThat(progress.last()).isEqualTo(1f)
    assertThat(progress.size, name = "per-chunk progress emissions").isGreaterThan(3)
    assertThat(progress, name = "progress must be monotonic").isEqualTo(progress.sorted())
    assertThat(outputFile.readBytes()).isEqualTo(payload)
  }

  @Test fun `reports only start and end progress when content-length is missing`() {
    val engine = MockEngine { respond(ByteReadChannel(payload), HttpStatusCode.OK) }

    val progress = download(engine)

    assertThat(progress).containsExactly(0f, 1f)
    assertThat(outputFile.readBytes()).isEqualTo(payload)
  }

  @Test fun `creates missing parent directories`() {
    val nested = dir.resolve("shows/some-show/episode.bin")
    val engine = MockEngine { respond(payload, HttpStatusCode.OK) }

    download(engine, nested)

    assertThat(nested.readBytes()).isEqualTo(payload)
  }

  @Test fun `sends the pinned user-agent`() {
    val engine = MockEngine { respond(payload, HttpStatusCode.OK) }

    download(engine)

    assertThat(engine.requestHistory.single().headers[HttpHeaders.UserAgent]).isEqualTo("okhttp/4.12.0")
  }

  @Test fun `downloadFile can override the user-agent per call`() {
    val engine = MockEngine { respond(payload, HttpStatusCode.OK) }

    runBlocking {
      Downloader(httpClient = HttpClient(engine))
        .downloadFile(url = URL, outputFile = outputFile.toOkioPath(), overwrite = false, userAgent = "curl/8.5.0")
        .toList()
    }

    assertThat(engine.requestHistory.single().headers[HttpHeaders.UserAgent]).isEqualTo("curl/8.5.0")
  }

  @Test fun `probe requests a single byte and parses the total from a 206 content-range`() {
    val engine = MockEngine {
      respond(
        content = ByteArray(1),
        status = HttpStatusCode.PartialContent,
        headers = headersOf(HttpHeaders.ContentRange, "bytes 0-0/66843410"),
      )
    }

    val result = runBlocking { Downloader(httpClient = HttpClient(engine)).probe(URL) }

    assertThat(engine.requestHistory.single().headers[HttpHeaders.Range]).isEqualTo("bytes=0-0")
    assertThat(result?.contentLength).isEqualTo(66_843_410L)
  }

  @Test fun `probe reports the content-length and final url across redirects`() {
    val redirectTarget = "https://cdn.example.com/variant/abc.mp3?media_type=dynamic"
    val engine = MockEngine { request ->
      when (request.url.host) {
        "example.com" -> respond(
          content = ByteArray(0),
          status = HttpStatusCode.Found,
          headers = headersOf(HttpHeaders.Location, redirectTarget),
        )
        else          -> respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
      }
    }

    val result = runBlocking { Downloader(httpClient = HttpClient(engine)).probe(URL, userAgent = "curl/8.5.0") }

    assertThat(result?.finalUrl).isEqualTo(redirectTarget)
    assertThat(result?.contentLength).isEqualTo(payload.size.toLong())
    engine.requestHistory.forEach {
      assertThat(it.headers[HttpHeaders.UserAgent]).isEqualTo("curl/8.5.0")
    }
  }

  @Test fun `probe returns null on a failure status`() {
    val engine = MockEngine { respond("gone", HttpStatusCode.NotFound) }

    val result = runBlocking { Downloader(httpClient = HttpClient(engine)).probe(URL) }

    assertThat(result).isNull()
  }

  private fun download(engine: MockEngine, file: java.io.File = outputFile): List<Float> = runBlocking {
    Downloader(httpClient = HttpClient(engine))
      .downloadFile(url = URL, outputFile = file.toOkioPath(), overwrite = false)
      .toList()
  }
}

private const val URL = "https://example.com/file.bin"
