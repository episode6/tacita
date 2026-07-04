package com.episode6.tacita.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixtures (see src/test/resources/audio):
 * - stitched.mp3: three independently-encoded mp3s concatenated — 6s tone (with ID3v2
 *   header), 2s tone, 8s tone — mimicking dynamic ad insertion.
 * - single.mp3: one continuous 10s encode (with ID3v2 header).
 */
class Mp3SegmentParserTest {

  private val parser = Mp3SegmentParser()

  @Test fun `stitched file splits into its source segments`() {
    val data = fixture("stitched.mp3")

    val scan = parser.scan(data)

    assertEquals(3, scan.segments.size)
    assertDurationsClose(listOf(6.0, 2.0, 8.0), scan.segments.map { it.durationSeconds })
    assertTrue(abs(scan.totalDurationSeconds - 16.0) < DURATION_TOLERANCE_SECONDS)
  }

  @Test fun `segments are contiguous and cover the stream after the id3 header`() {
    val data = fixture("stitched.mp3")

    val scan = parser.scan(data)

    assertTrue(scan.leadingBytes > 0) // fixture has an ID3v2 header
    assertEquals(scan.leadingBytes, scan.segments.first().startByte)
    assertEquals(data.size, scan.segments.last().endByte)
    scan.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endByte, b.startByte) }
  }

  @Test fun `continuous encode yields a single segment`() {
    val data = fixture("single.mp3")

    val scan = parser.scan(data)

    assertEquals(1, scan.segments.size)
    assertTrue(abs(scan.totalDurationSeconds - 10.0) < DURATION_TOLERANCE_SECONDS)
  }

  @Test fun `non-mp3 data yields no segments`() {
    val scan = parser.scan(ByteArray(1024) { it.toByte() })

    assertEquals(0, scan.segments.size)
    assertEquals(0.0, scan.totalDurationSeconds)
  }

  private fun assertDurationsClose(expected: List<Double>, actual: List<Double>) {
    assertEquals(expected.size, actual.size)
    expected.zip(actual).forEach { (e, a) ->
      assertTrue(abs(e - a) < DURATION_TOLERANCE_SECONDS, "expected ${e}s but was ${a}s")
    }
  }
}

private const val DURATION_TOLERANCE_SECONDS = 0.5

internal fun fixture(name: String): ByteArray =
  checkNotNull(Mp3SegmentParserTest::class.java.getResourceAsStream("/audio/$name")) {
    "missing test fixture /audio/$name"
  }.readBytes()
