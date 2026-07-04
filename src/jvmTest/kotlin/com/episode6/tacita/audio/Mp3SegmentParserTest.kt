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

  // All fixtures are MPEG1/44.1kHz; the remaining tests cover the other frame formats
  // with synthetic zero-body frames (zeros can't contain a false 0xFF sync word).

  @Test fun `parses MPEG2 frame headers`() {
    // MPEG2 layer III, 80kbps, 22050Hz: 576/8 * 80000 / 22050 = 261 bytes per frame
    val data = repeatFrame(count = 3, header = frameHeader(version = MPEG2, bitrateIndex = 9, sampleRateIndex = 0), lengthBytes = 261)

    val frames = parser.frames(data, from = 0, until = data.size)

    assertEquals(3, frames.size)
    frames.forEach {
      assertEquals(261, it.endByte - it.startByte)
      assertEquals(576.0 / 22050, it.durationSeconds, 1e-9)
    }
  }

  @Test fun `parses MPEG2_5 frame headers`() {
    // MPEG2.5 layer III, 16kbps, 11025Hz: 576/8 * 16000 / 11025 = 104 bytes per frame
    val data = repeatFrame(count = 3, header = frameHeader(version = MPEG2_5, bitrateIndex = 2, sampleRateIndex = 0), lengthBytes = 104)

    val frames = parser.frames(data, from = 0, until = data.size)

    assertEquals(3, frames.size)
    frames.forEach {
      assertEquals(104, it.endByte - it.startByte)
      assertEquals(576.0 / 11025, it.durationSeconds, 1e-9)
    }
  }

  @Test fun `honors the padding bit in frame length`() {
    val data = repeatFrame(count = 2, header = frameHeader(version = MPEG2, bitrateIndex = 9, sampleRateIndex = 0, padding = 1), lengthBytes = 262)

    val frames = parser.frames(data, from = 0, until = data.size)

    assertEquals(2, frames.size)
    frames.forEach { assertEquals(262, it.endByte - it.startByte) }
  }

  @Test fun `excludes a truncated final frame`() {
    val header = frameHeader(version = MPEG2, bitrateIndex = 9, sampleRateIndex = 0)
    val data = repeatFrame(count = 2, header = header, lengthBytes = 261) +
      (header + ByteArray(100)) // final frame cut short mid-body

    val frames = parser.frames(data, from = 0, until = data.size)

    assertEquals(2, frames.size)
    assertEquals(2 * 261, frames.last().endByte)
  }

  @Test fun `scans a continuous MPEG2 stream as a single segment`() {
    val data = repeatFrame(count = 100, header = frameHeader(version = MPEG2, bitrateIndex = 9, sampleRateIndex = 0), lengthBytes = 261)

    val scan = parser.scan(data)

    assertEquals(1, scan.segments.size)
    assertEquals(0, scan.leadingBytes)
    assertEquals(100 * 576.0 / 22050, scan.totalDurationSeconds, 1e-6)
  }

  /** Header for an mp3 layer III frame; [version] is the 2-bit header field value. */
  private fun frameHeader(version: Int, bitrateIndex: Int, sampleRateIndex: Int, padding: Int = 0): ByteArray =
    byteArrayOf(
      0xFF.toByte(),
      (0xE0 or (version shl 3) or (1 shl 1) or 1).toByte(), // sync | version | layer III | no crc
      ((bitrateIndex shl 4) or (sampleRateIndex shl 2) or (padding shl 1)).toByte(),
      0x00,
    )

  private fun repeatFrame(count: Int, header: ByteArray, lengthBytes: Int): ByteArray {
    val frame = header + ByteArray(lengthBytes - header.size)
    var data = ByteArray(0)
    repeat(count) { data += frame }
    return data
  }

  private fun assertDurationsClose(expected: List<Double>, actual: List<Double>) {
    assertEquals(expected.size, actual.size)
    expected.zip(actual).forEach { (e, a) ->
      assertTrue(abs(e - a) < DURATION_TOLERANCE_SECONDS, "expected ${e}s but was ${a}s")
    }
  }
}

private const val DURATION_TOLERANCE_SECONDS = 0.5

// 2-bit version field values from the mp3 frame header
private const val MPEG2 = 2
private const val MPEG2_5 = 0

internal fun fixture(name: String): ByteArray =
  checkNotNull(Mp3SegmentParserTest::class.java.getResourceAsStream("/audio/$name")) {
    "missing test fixture /audio/$name"
  }.readBytes()
