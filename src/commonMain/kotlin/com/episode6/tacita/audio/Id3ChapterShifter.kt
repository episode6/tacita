package com.episode6.tacita.audio

/**
 * Shifts CHAP frame timestamps (and byte offsets) in an ID3v2 header to account for byte
 * ranges cut from the audio stream, so chapter marks still point at the right moments
 * after ads are removed. All fields are fixed-width and rewritten in place, leaving the
 * tag's size and layout untouched. Tags using features that make in-place editing unsafe
 * (unsynchronisation, extended headers, non-default frame format flags) are left as-is —
 * [Id3FrameReader] applies the same bail-outs when walking the tag.
 */
internal class Id3ChapterShifter(
  private val reader: Id3FrameReader = Id3FrameReader(),
) {

  data class CutRange(val fromByte: Int, val toByte: Int, val fromMs: Long, val toMs: Long)

  fun shift(id3: ByteArray, cuts: List<CutRange>): ByteArray {
    if (cuts.isEmpty()) return id3
    val chapFrames = reader.frames(id3)
      .filter { it.id == "CHAP" && it.statusFlags == 0 && it.formatFlags == 0 }
    if (chapFrames.isEmpty()) return id3 // nothing to edit: keep the original bytes

    val out = id3.copyOf()
    chapFrames.forEach { shiftChapterFrame(out, bodyStart = it.bodyStart, bodyEnd = it.bodyEnd, cuts = cuts) }
    return out
  }

  /** CHAP body: null-terminated element id, then start ms, end ms, start byte, end byte. */
  private fun shiftChapterFrame(out: ByteArray, bodyStart: Int, bodyEnd: Int, cuts: List<CutRange>) {
    var p = bodyStart
    while (p < bodyEnd && out[p] != 0.toByte()) p++
    p++
    if (p + 16 > bodyEnd) return
    writeInt32(out, p, shiftTime(id3Int32(out, p), cuts))
    writeInt32(out, p + 4, shiftTime(id3Int32(out, p + 4), cuts))
    if (id3Int32(out, p + 8) != NO_OFFSET) writeInt32(out, p + 8, shiftOffset(id3Int32(out, p + 8), cuts))
    if (id3Int32(out, p + 12) != NO_OFFSET) writeInt32(out, p + 12, shiftOffset(id3Int32(out, p + 12), cuts))
  }

  private fun shiftTime(ms: Long, cuts: List<CutRange>): Long =
    ms - cuts.sumOf { (minOf(ms, it.toMs) - it.fromMs).coerceAtLeast(0) }

  private fun shiftOffset(byte: Long, cuts: List<CutRange>): Long =
    byte - cuts.sumOf { (minOf(byte, it.toByte.toLong()) - it.fromByte).coerceAtLeast(0) }

  private fun writeInt32(bytes: ByteArray, at: Int, value: Long) {
    val v = value.coerceIn(0, 0xFFFFFFFFL)
    bytes[at] = (v shr 24).toByte()
    bytes[at + 1] = (v shr 16).toByte()
    bytes[at + 2] = (v shr 8).toByte()
    bytes[at + 3] = v.toByte()
  }
}

private const val NO_OFFSET = 0xFFFFFFFFL
