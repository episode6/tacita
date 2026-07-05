package com.episode6.tacita.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isZero
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Byte counts mirror the 2026-07-04 field research (see docs/ALGORITHM.md): a 4170s
 * 128kbps episode is ~66.8MB clean and ~70.5MB with app-tier fill.
 */
class CleanSourceResolverTest {

  private val logLines = mutableListOf<String>()

  @Test fun `returns null without probing when the feed declares no expectations`() {
    val engine = MockEngine { respond("should not be called", HttpStatusCode.OK) }

    val result = resolve(engine, declaredBytes = null, durationSeconds = null)

    assertThat(result).isNull()
    assertThat(engine.requestHistory.size).isZero()
  }

  @Test fun `accepts the pinned-tier serving when it matches the declared enclosure bytes`() {
    val engine = MockEngine { serve(CLEAN_BYTES) }

    val result = resolve(engine, declaredBytes = CLEAN_BYTES)

    assertThat(result).isEqualTo(CleanSourceResolver.CleanSource(EPISODE_URL, userAgent = null, contentLength = CLEAN_BYTES))
  }

  @Test fun `accepts the pinned-tier serving when it matches the expected duration at a standard bitrate`() {
    val engine = MockEngine { serve(CLEAN_BYTES) }

    val result = resolve(engine, durationSeconds = DURATION_SECONDS)

    assertThat(result?.contentLength).isEqualTo(CLEAN_BYTES)
  }

  @Test fun `rejects a filled serving via the duration check and returns null when nothing else validates`() {
    val engine = MockEngine { serve(FILLED_BYTES) } // every tier serves fill

    val result = resolve(engine, durationSeconds = DURATION_SECONDS)

    assertThat(result).isNull()
  }

  @Test fun `rejects a truncated serving via the duration check`() {
    val engine = MockEngine { serve(CLEAN_BYTES - 2_000_000) }

    val result = resolve(engine, durationSeconds = DURATION_SECONDS)

    assertThat(result).isNull()
  }

  @Test fun `uses the static fallback_url leaked in the resolved redirect chain`() {
    val engine = MockEngine { request ->
      when {
        request.url.toString().startsWith(STATIC_URL) -> serve(CLEAN_BYTES)
        request.url.host == "example.com"             -> respond(
          content = ByteArray(0),
          status = HttpStatusCode.Found,
          headers = headersOf(HttpHeaders.Location, VARIANT_URL),
        )
        else                                          -> serve(FILLED_BYTES) // the dynamic variant
      }
    }

    val result = resolve(engine, durationSeconds = DURATION_SECONDS)

    assertThat(result).isEqualTo(CleanSourceResolver.CleanSource(STATIC_URL, userAgent = null, contentLength = CLEAN_BYTES))
    assertThat(logLines.filter { "dai metadata" in it }.single()).isEqualTo(
      "CleanSourceResolver: dai metadata in resolved url: [m=[1478560,2682539], al=4170360, ab=128]"
    )
  }

  @Test fun `falls back to bot-tier user-agents when the pinned tier is filled`() {
    val engine = MockEngine { request ->
      when (request.headers[HttpHeaders.UserAgent]) {
        "curl/8.5.0" -> serve(CLEAN_BYTES)
        else         -> serve(FILLED_BYTES)
      }
    }

    val result = resolve(engine, declaredBytes = CLEAN_BYTES)

    assertThat(result).isEqualTo(CleanSourceResolver.CleanSource(EPISODE_URL, userAgent = "curl/8.5.0", contentLength = CLEAN_BYTES))
  }

  @Test fun `a probe failure skips that candidate instead of failing resolution`() {
    val engine = MockEngine { request ->
      when (request.headers[HttpHeaders.UserAgent]) {
        "okhttp/4.12.0" -> respond("boom", HttpStatusCode.InternalServerError)
        "curl/8.5.0"    -> serve(CLEAN_BYTES)
        else            -> serve(FILLED_BYTES)
      }
    }

    val result = resolve(engine, declaredBytes = CLEAN_BYTES)

    assertThat(result?.userAgent).isEqualTo("curl/8.5.0")
  }

  private fun MockRequestHandleScope.serve(totalBytes: Long): HttpResponseData = respond(
    content = ByteArray(1),
    status = HttpStatusCode.PartialContent,
    headers = headersOf(HttpHeaders.ContentRange, "bytes 0-0/$totalBytes"),
  )

  private fun resolve(engine: MockEngine, declaredBytes: Long? = null, durationSeconds: Long? = null): CleanSourceResolver.CleanSource? =
    runBlocking {
      CleanSourceResolver(
        downloader = Downloader(httpClient = HttpClient(engine)),
        log = { logLines += it },
      ).resolve(EPISODE_URL, declaredEnclosureBytes = declaredBytes, expectedDurationSeconds = durationSeconds)
    }
}

private const val EPISODE_URL = "https://example.com/episode.mp3"
private const val STATIC_URL = "https://cdn.example.com/attachments/12345/episode.mp3"
private val VARIANT_URL = "https://cdn.example.com/v1/variant/abc.mp3?media_type=dynamic" +
  "&m=${"[1478560,2682539]".encodeURLParameter()}&al=4170360&ab=128" +
  "&fallback_url=${STATIC_URL.encodeURLParameter()}"

private const val DURATION_SECONDS = 4170L
private const val CLEAN_BYTES = 66_843_410L // duration * 128kbps + ~117KB of ID3 overhead
private const val FILLED_BYTES = 70_539_410L // same episode with ~231s of injected fill
