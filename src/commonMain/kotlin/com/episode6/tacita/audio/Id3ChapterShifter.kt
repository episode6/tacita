package com.episode6.tacita.audio

/**
 * Shifts CHAP frame timestamps (and byte offsets) in an ID3v2 header to account for byte
 * ranges cut from the audio stream, so chapter marks still point at the right moments
 * after ads are removed. All fields are fixed-width and rewritten in place, leaving the
 * tag's size and layout untouched. Tags using features that make in-place editing unsafe
 * (unsynchronisation, extended headers, non-default frame format flags) are left as-is.
 */
internal object Id3ChapterShifter {

  data class CutRange(val fromByte: Int, val toByte: Int, val fromMs: Long, val toMs: Long)

  fun shift(id3: ByteArray, cuts: List<CutRange>): ByteArray {
    if (cuts.isEmpty() || id3.size < 10) return id3
    if (id3[0] != 'I'.code.toByte() || id3[1] != 'D'.code.toByte() || id3[2] != '3'.code.toByte()) return id3
    val version = id3[3].toInt()
    if (version !in 3..4) return id3
    if (id3[5].toInt() and 0xC0 != 0) return id3 // unsynchronisation or extended header

    val out = id3.copyOf()
    val tagEnd = minOf(id3.size, 10 + syncsafe(id3, 6))
    var pos = 10
    while (pos + 10 <= tagEnd) {
      val frameId = out.decodeToString(pos, pos + 4)
      if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break // padding or garbage
      val size = if (version == 4) syncsafe(out, pos + 4) else int32(out, pos + 4).toInt()
      if (size <= 0 || pos + 10 + size > tagEnd) break
      if (frameId == "CHAP" && out[pos + 8].toInt() == 0 && out[pos + 9].toInt() == 0) {
        shiftChapterFrame(out, bodyStart = pos + 10, bodyEnd = pos + 10 + size, cuts = cuts)
      }
      pos += 10 + size
    }
    return out
  }

  /** CHAP body: null-terminated element id, then start ms, end ms, start byte, end byte. */
  private fun shiftChapterFrame(out: ByteArray, bodyStart: Int, bodyEnd: Int, cuts: List<CutRange>) {
    var p = bodyStart
    while (p < bodyEnd && out[p] != 0.toByte()) p++
    p++
    if (p + 16 > bodyEnd) return
    writeInt32(out, p, shiftTime(int32(out, p), cuts))
    writeInt32(out, p + 4, shiftTime(int32(out, p + 4), cuts))
    if (int32(out, p + 8) != NO_OFFSET) writeInt32(out, p + 8, shiftOffset(int32(out, p + 8), cuts))
    if (int32(out, p + 12) != NO_OFFSET) writeInt32(out, p + 12, shiftOffset(int32(out, p + 12), cuts))
  }

  private fun shiftTime(ms: Long, cuts: List<CutRange>): Long =
    ms - cuts.sumOf { (minOf(ms, it.toMs) - it.fromMs).coerceAtLeast(0) }

  private fun shiftOffset(byte: Long, cuts: List<CutRange>): Long =
    byte - cuts.sumOf { (minOf(byte, it.toByte.toLong()) - it.fromByte).coerceAtLeast(0) }

  private fun syncsafe(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0x7F shl 21) or
      (bytes[at + 1].toInt() and 0x7F shl 14) or
      (bytes[at + 2].toInt() and 0x7F shl 7) or
      (bytes[at + 3].toInt() and 0x7F)

  private fun int32(bytes: ByteArray, at: Int): Long =
    (bytes[at].toLong() and 0xFF shl 24) or
      (bytes[at + 1].toLong() and 0xFF shl 16) or
      (bytes[at + 2].toLong() and 0xFF shl 8) or
      (bytes[at + 3].toLong() and 0xFF)

  private fun writeInt32(bytes: ByteArray, at: Int, value: Long) {
    val v = value.coerceIn(0, 0xFFFFFFFFL)
    bytes[at] = (v shr 24).toByte()
    bytes[at + 1] = (v shr 16).toByte()
    bytes[at + 2] = (v shr 8).toByte()
    bytes[at + 3] = v.toByte()
  }

  private const val NO_OFFSET = 0xFFFFFFFFL
}
