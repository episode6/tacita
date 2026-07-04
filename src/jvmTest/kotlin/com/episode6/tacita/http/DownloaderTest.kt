package com.episode6.tacita.http

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloaderTest {

  private val dir = Files.createTempDirectory("downloader-test").toFile().apply { deleteOnExit() }
  private val outputFile = dir.resolve("file.bin")
  private val payload = ByteArray(50_000) { it.toByte() } // several read chunks worth

  @Test fun `throws on non-2xx status and leaves no file behind`() {
    val engine = MockEngine { respond("gone", HttpStatusCode.NotFound) }

    val e = assertFailsWith<IOException> { download(engine) }

    assertTrue("404" in e.message.orEmpty(), "message should name the status: ${e.message}")
    assertFalse(outputFile.exists(), "the error body must not be saved as a download")
  }

  @Test fun `mid-download failure deletes the partial file`() {
    val engine = MockEngine {
      val channel = ByteChannel(autoFlush = true)
      channel.writeFully(payload, 0, 1_000)
      channel.close(IOException("connection reset"))
      respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
    }

    assertFailsWith<IOException> { download(engine) }

    assertFalse(outputFile.exists(), "a partial download must not be left on disk")
  }

  @Test fun `reports granular progress when content-length is known`() {
    val engine = MockEngine {
      respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
    }

    val progress = download(engine)

    assertEquals(0f, progress.first())
    assertEquals(1f, progress.last())
    assertTrue(progress.size > 3, "expected per-chunk progress, got $progress")
    assertEquals(progress.sorted(), progress, "progress must be monotonic: $progress")
    assertContentEquals(payload, outputFile.readBytes())
  }

  @Test fun `reports only start and end progress when content-length is missing`() {
    val engine = MockEngine { respond(ByteReadChannel(payload), HttpStatusCode.OK) }

    val progress = download(engine)

    assertEquals(listOf(0f, 1f), progress)
    assertContentEquals(payload, outputFile.readBytes())
  }

  @Test fun `creates missing parent directories`() {
    val nested = dir.resolve("shows/some-show/episode.bin")
    val engine = MockEngine { respond(payload, HttpStatusCode.OK) }

    download(engine, nested)

    assertContentEquals(payload, nested.readBytes())
  }

  @Test fun `sends the pinned user-agent`() {
    val engine = MockEngine { respond(payload, HttpStatusCode.OK) }

    download(engine)

    assertEquals("okhttp/4.12.0", engine.requestHistory.single().headers[HttpHeaders.UserAgent])
  }

  private fun download(engine: MockEngine, file: java.io.File = outputFile): List<Float> = runBlocking {
    Downloader(httpClient = HttpClient(engine))
      .downloadFile(url = URL, outputFile = file.toOkioPath(), overwrite = false)
      .toList()
  }
}

private const val URL = "https://example.com/file.bin"
