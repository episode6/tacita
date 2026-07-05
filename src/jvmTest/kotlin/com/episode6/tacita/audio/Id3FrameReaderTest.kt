package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test

class Id3FrameReaderTest {

  private val reader = Id3FrameReader()

  @Test fun `reads chapter times from a v2_3 tag`() {
    // ch0's body is >127 bytes so its size only decodes correctly as a plain int32
    val id3 = id3(version = 3, chap(id = "ch0".padEnd(120, 'x'), fromMs = 0, toMs = 5_000), chap(id = "ch1", fromMs = 10_000, toMs = 14_000))

    assertThat(reader.chapters(id3)).containsExactly(
      Id3FrameReader.Chapter(startMs = 0, endMs = 5_000),
      Id3FrameReader.Chapter(startMs = 10_000, endMs = 14_000),
    )
  }

  @Test fun `reads chapter times from a v2_4 tag`() {
    // same shape as the v2.3 test, but ch0's >127-byte size is syncsafe-encoded
    val id3 = id3(version = 4, chap(id = "ch0".padEnd(120, 'x'), fromMs = 0, toMs = 5_000), chap(id = "ch1", fromMs = 10_000, toMs = 14_000))

    assertThat(reader.chapters(id3)).containsExactly(
      Id3FrameReader.Chapter(startMs = 0, endMs = 5_000),
      Id3FrameReader.Chapter(startMs = 10_000, endMs = 14_000),
    )
  }

  @Test fun `reads the tag at the start of a whole mp3 file`() {
    val file = id3(version = 3, chap(id = "ch0", fromMs = 0, toMs = 5_000)) + ByteArray(1024) { 0xFF.toByte() }

    assertThat(reader.chapters(file)).containsExactly(Id3FrameReader.Chapter(startMs = 0, endMs = 5_000))
  }

  @Test fun `stops at padding but keeps the frames before it`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 0, toMs = 5_000), padding = 32)

    assertThat(reader.chapters(id3)).containsExactly(Id3FrameReader.Chapter(startMs = 0, endMs = 5_000))
  }

  @Test fun `excludes CHAP frames with non-default format flags`() {
    val id3 = id3(
      version = 3,
      chap(id = "ch0", fromMs = 0, toMs = 5_000, formatFlags = 0x40), // compressed: layout is opaque
      chap(id = "ch1", fromMs = 10_000, toMs = 14_000),
    )

    assertThat(reader.chapters(id3)).containsExactly(Id3FrameReader.Chapter(startMs = 10_000, endMs = 14_000))
  }

  @Test fun `returns nothing for an unsynchronised tag`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 0, toMs = 5_000)).apply { this[5] = 0x80.toByte() }

    assertThat(reader.frames(id3)).isEmpty()
  }

  @Test fun `returns nothing for a tag with an extended header`() {
    val id3 = id3(version = 3, chap(id = "ch0", fromMs = 0, toMs = 5_000)).apply { this[5] = 0x40.toByte() }

    assertThat(reader.frames(id3)).isEmpty()
  }

  @Test fun `returns nothing for unsupported id3 versions`() {
    val id3 = id3(version = 2, chap(id = "ch0", fromMs = 0, toMs = 5_000))

    assertThat(reader.frames(id3)).isEmpty()
  }

  @Test fun `returns nothing for non-id3 leading bytes`() {
    val notId3 = ByteArray(64) { it.toByte() }

    assertThat(reader.frames(notId3)).isEmpty()
  }

  // -- fixture builders (fixed-width CHAP layout per the id3v2 chapter addendum) --

  private data class Chap(val id: String, val fromMs: Long, val toMs: Long, val formatFlags: Int)

  private fun chap(id: String, fromMs: Long, toMs: Long, formatFlags: Int = 0) = Chap(id, fromMs, toMs, formatFlags)

  private fun id3(version: Int, vararg chaps: Chap, padding: Int = 0): ByteArray {
    val body = chaps.map { c ->
      val frameBody = c.id.toByteArray(Charsets.ISO_8859_1) + 0 +
        int32(c.fromMs) + int32(c.toMs) + int32(NO_OFFSET) + int32(NO_OFFSET)
      val size = if (version == 4) syncsafe4(frameBody.size) else int32(frameBody.size.toLong())
      "CHAP".toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, c.formatFlags.toByte()) + frameBody
    }.reduce(ByteArray::plus) + ByteArray(padding)
    return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0) +
      syncsafe4(body.size) + body
  }

  private fun int32(value: Long) = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
  )

  private fun syncsafe4(size: Int) = byteArrayOf(
    (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
    (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
  )
}

private const val NO_OFFSET = 0xFFFFFFFFL
