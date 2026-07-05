package com.episode6.tacita.audio

/**
 * Read-only walker for the ID3v2.3/2.4 tag at the start of a byte stream. Refuses (returns
 * empty) the same tags [Id3ChapterShifter] declines to edit — non-ID3 lead, unsupported
 * version, unsynchronisation, extended header — because an unsynchronised tag can't be
 * byte-walked safely. A whole mp3 file can be passed in; the walk self-bounds at the tag's
 * declared size.
 */
internal class Id3FrameReader {

  data class FrameSlice(
    val id: String,
    val statusFlags: Int,
    val formatFlags: Int,
    val bodyStart: Int,
    val bodyEnd: Int, // exclusive
  )

  data class Chapter(val startMs: Long, val endMs: Long)

  /** Top-level frames of the tag at the start of [data]; empty when there's no readable tag. */
  fun frames(data: ByteArray): List<FrameSlice> {
    if (data.size < 10) return emptyList()
    if (data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return emptyList()
    val version = data[3].toInt()
    if (version !in 3..4) return emptyList()
    if (data[5].toInt() and 0xC0 != 0) return emptyList() // unsynchronisation or extended header

    val frames = mutableListOf<FrameSlice>()
    val tagEnd = minOf(data.size, 10 + id3Syncsafe(data, 6))
    var pos = 10
    while (pos + 10 <= tagEnd) {
      val frameId = data.decodeToString(pos, pos + 4)
      if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break // padding or garbage
      val size = if (version == 4) id3Syncsafe(data, pos + 4) else id3Int32(data, pos + 4).toInt()
      if (size <= 0 || pos + 10 + size > tagEnd) break
      frames += FrameSlice(
        id = frameId,
        statusFlags = data[pos + 8].toInt(),
        formatFlags = data[pos + 9].toInt(),
        bodyStart = pos + 10,
        bodyEnd = pos + 10 + size,
      )
      pos += 10 + size
    }
    return frames
  }

  /** CHAP frame times, in tag order (default-flag frames only, like [Id3ChapterShifter]). */
  fun chapters(data: ByteArray): List<Chapter> =
    frames(data)
      .filter { it.id == "CHAP" && it.statusFlags == 0 && it.formatFlags == 0 }
      .mapNotNull { chapterTimes(data, it.bodyStart, it.bodyEnd) }

  /** CHAP body: null-terminated element id, then start ms, end ms, start byte, end byte. */
  private fun chapterTimes(data: ByteArray, bodyStart: Int, bodyEnd: Int): Chapter? {
    var p = bodyStart
    while (p < bodyEnd && data[p] != 0.toByte()) p++
    p++
    if (p + 16 > bodyEnd) return null
    return Chapter(startMs = id3Int32(data, p), endMs = id3Int32(data, p + 4))
  }
}

internal fun id3Syncsafe(bytes: ByteArray, at: Int): Int =
  (bytes[at].toInt() and 0x7F shl 21) or
    (bytes[at + 1].toInt() and 0x7F shl 14) or
    (bytes[at + 2].toInt() and 0x7F shl 7) or
    (bytes[at + 3].toInt() and 0x7F)

internal fun id3Int32(bytes: ByteArray, at: Int): Long =
  (bytes[at].toLong() and 0xFF shl 24) or
    (bytes[at + 1].toLong() and 0xFF shl 16) or
    (bytes[at + 2].toLong() and 0xFF shl 8) or
    (bytes[at + 3].toLong() and 0xFF)
