package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.episode6.tacita.audio.Id3ChapterShifter.CutRange
import kotlin.test.Test

class Id3ChapterShifterTest {

  private val shifter = Id3ChapterShifter()

  // a 3s cut spanning bytes 10_000..40_000 and 5_000ms..8_000ms of the stream
  private val cut = CutRange(fromByte = 10_000, toByte = 40_000, fromMs = 5_000, toMs = 8_000)

  @Test fun `shifts chapters after the cut in a v2_3 tag`() {
    // ch0's body is >127 bytes so its size only decodes correctly as a plain int32
    val id3 = id3(version = 3, chap(id = "ch0".padEnd(120, 'x'), fromMs = 0, toMs = 5_000), chap(id = "ch1", fromMs = 10_000, toMs = 14_000))

    val chapters = parseChapterTimes(shifter.shift(id3, listOf(cut)))

    assertThat(chapters).containsExactly(0L to 5_000L, 7_000L to 11_000L)
  }

  @Test fun `shifts chapters after the cut in a v2_4 tag`() {
    // same shape as the v2.3 test, but ch0's >127-byte size is syncsafe-encoded
    val id3 = id3(version = 4, chap(id = "ch0".padEnd(120, 'x'), fromMs = 0, toMs = 5_000), chap(id = "ch1", fromMs = 10_000, toMs = 14_000))

    val chapters = parseChapterTimes(shifter.shift(id3, listOf(cut)))

    assertThat(chapters).containsExactly(0L to 5_000L, 7_000L to 11_000L)
  }

  @Test fun `clamps a chapter overlapping the cut range to the cut point`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 6_000, toMs = 10_000))

    val chapters = parseChapterTimes(shifter.shift(id3, listOf(cut)))

    assertThat(chapters).containsExactly(5_000L to 7_000L)
  }

  @Test fun `collapses a chapter entirely inside the cut range`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 6_000, toMs = 7_500))

    val chapters = parseChapterTimes(shifter.shift(id3, listOf(cut)))

    assertThat(chapters).containsExactly(5_000L to 5_000L)
  }

  @Test fun `accumulates multiple cuts before a chapter`() {
    val cuts = listOf(
      CutRange(fromByte = 100, toByte = 200, fromMs = 1_000, toMs = 2_000),
      CutRange(fromByte = 300, toByte = 400, fromMs = 5_000, toMs = 8_000),
    )
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 10_000, toMs = 14_000))

    val chapters = parseChapterTimes(shifter.shift(id3, cuts))

    assertThat(chapters).containsExactly(6_000L to 10_000L)
  }

  @Test fun `shifts real byte offsets by the bytes cut before them`() {
    val id3 = id3(
      version = 3,
      chap(id = "ch0", fromMs = 0, toMs = 4_000, fromByte = 500, toByte = 9_000), // before the cut
      chap(id = "ch1", fromMs = 10_000, toMs = 14_000, fromByte = 50_000, toByte = 80_000), // after the cut
    )

    val offsets = parseChapterOffsets(shifter.shift(id3, listOf(cut)))

    assertThat(offsets).containsExactly(500L to 9_000L, 20_000L to 50_000L)
  }

  @Test fun `leaves the no-offset sentinel untouched`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 10_000, toMs = 14_000))

    val offsets = parseChapterOffsets(shifter.shift(id3, listOf(cut)))

    assertThat(offsets).containsExactly(NO_OFFSET to NO_OFFSET)
  }

  @Test fun `bails out on unsynchronised tags`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 10_000, toMs = 14_000)).apply { this[5] = 0x80.toByte() }

    assertThat(shifter.shift(id3, listOf(cut))).isEqualTo(id3)
  }

  @Test fun `bails out on tags with an extended header`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 10_000, toMs = 14_000)).apply { this[5] = 0x40.toByte() }

    assertThat(shifter.shift(id3, listOf(cut))).isEqualTo(id3)
  }

  @Test fun `bails out on unsupported id3 versions`() {
    val id3 = id3(version = 2, chap(id = "ch0", fromMs = 10_000, toMs = 14_000))

    assertThat(shifter.shift(id3, listOf(cut))).isEqualTo(id3)
  }

  @Test fun `bails out on non-id3 leading bytes`() {
    val notId3 = ByteArray(64) { it.toByte() }

    assertThat(shifter.shift(notId3, listOf(cut))).isEqualTo(notId3)
  }

  @Test fun `skips CHAP frames with non-default format flags but shifts the rest`() {
    val id3 = id3(
      version = 3,
      chap(id = "ch0", fromMs = 10_000, toMs = 14_000, formatFlags = 0x40), // compressed: unsafe to edit
      chap(id = "ch1", fromMs = 10_000, toMs = 14_000),
    )

    val chapters = parseChapterTimes(shifter.shift(id3, listOf(cut)))

    assertThat(chapters).containsExactly(10_000L to 14_000L, 7_000L to 11_000L)
  }

  // -- fixture builders / parsers (fixed-width CHAP layout per the id3v2 chapter addendum) --

  private data class Chap(
    val id: String,
    val fromMs: Long,
    val toMs: Long,
    val fromByte: Long,
    val toByte: Long,
    val formatFlags: Int,
  )

  private fun chap(
    id: String,
    fromMs: Long,
    toMs: Long,
    fromByte: Long = NO_OFFSET,
    toByte: Long = NO_OFFSET,
    formatFlags: Int = 0,
  ) = Chap(id, fromMs, toMs, fromByte, toByte, formatFlags)

  private fun id3(version: Int, vararg chaps: Chap): ByteArray {
    val body = chaps.map { c ->
      val frameBody = c.id.toByteArray(Charsets.ISO_8859_1) + 0 +
        int32(c.fromMs) + int32(c.toMs) + int32(c.fromByte) + int32(c.toByte)
      val size = if (version == 4) syncsafe4(frameBody.size) else int32(frameBody.size.toLong())
      "CHAP".toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, c.formatFlags.toByte()) + frameBody
    }.reduce(ByteArray::plus)
    return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0) +
      syncsafe4(body.size) + body
  }

  /** [fromMs, toMs, fromByte, toByte] per CHAP frame, decoding frame sizes per the tag's version. */
  private fun parseChapters(data: ByteArray): List<LongArray> {
    val version = data[3].toInt()
    val chapters = mutableListOf<LongArray>()
    var pos = 10
    while (pos + 10 < data.size && String(data, pos, 4, Charsets.ISO_8859_1) == "CHAP") {
      val size = if (version == 4) syncsafeAt(data, pos + 4) else int32At(data, pos + 4).toInt()
      var p = pos + 10
      while (data[p] != 0.toByte()) p++
      p++
      chapters += longArrayOf(int32At(data, p), int32At(data, p + 4), int32At(data, p + 8), int32At(data, p + 12))
      pos += 10 + size
    }
    return chapters
  }

  private fun parseChapterTimes(data: ByteArray) = parseChapters(data).map { it[0] to it[1] }
  private fun parseChapterOffsets(data: ByteArray) = parseChapters(data).map { it[2] to it[3] }

  private fun int32(value: Long) = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
  )

  private fun int32At(data: ByteArray, at: Int): Long =
    (data[at].toLong() and 0xFF shl 24) or (data[at + 1].toLong() and 0xFF shl 16) or
      (data[at + 2].toLong() and 0xFF shl 8) or (data[at + 3].toLong() and 0xFF)

  private fun syncsafe4(size: Int) = byteArrayOf(
    (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
    (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
  )

  private fun syncsafeAt(data: ByteArray, at: Int): Int =
    (data[at].toInt() and 0x7F shl 21) or (data[at + 1].toInt() and 0x7F shl 14) or
      (data[at + 2].toInt() and 0x7F shl 7) or (data[at + 3].toInt() and 0x7F)
}

private const val NO_OFFSET = 0xFFFFFFFFL
