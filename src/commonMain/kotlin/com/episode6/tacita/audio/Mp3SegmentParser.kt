package com.episode6.tacita.audio

/**
 * Splits an mp3 stream into the independently-encoded segments it was stitched together from.
 *
 * Dynamic ad insertion (e.g. Acast) serves episodes as a concatenation of separately-encoded
 * mp3 files. Each such segment begins with a silent Xing/Info/LAME tag frame. A tag frame is
 * identified by its encoder tag string appearing near the start of the frame combined with a
 * high fraction of filler bytes — real audio frames are high-entropy, so the combination
 * doesn't occur in them by chance.
 */
public object Mp3SegmentParser {

  public data class Segment(
    public val startByte: Int,
    public val endByte: Int, // exclusive
    public val startSeconds: Double,
    public val durationSeconds: Double,
  )

  public data class Scan(
    public val leadingBytes: Int, // bytes before the first frame (ID3v2 header etc)
    public val segments: List<Segment>,
    public val totalDurationSeconds: Double,
  )

  internal data class Frame(
    val startByte: Int,
    val endByte: Int, // exclusive
    val durationSeconds: Double,
  )

  public fun scan(data: ByteArray): Scan {
    val firstFrame = findNextFrame(data, id3v2Size(data)) ?: return Scan(data.size, emptyList(), 0.0)
    var seconds = 0.0
    val bounds = mutableListOf(Boundary(byte = firstFrame, seconds = 0.0))
    for (frame in frames(data, from = firstFrame, until = data.size)) {
      if (frame.startByte != firstFrame && isTagFrame(data, frame.startByte, frame.endByte - frame.startByte)) {
        bounds += Boundary(byte = frame.startByte, seconds = seconds)
      }
      seconds += frame.durationSeconds
    }
    // LAME writes its version string + filler into the final flush frames of an encode,
    // which looks like a tag frame; a tiny trailing segment is that artifact, not a stitch.
    if (bounds.size > 1 && seconds - bounds.last().seconds < TRAILING_FLUSH_MERGE_SECONDS) {
      bounds.removeAt(bounds.lastIndex)
    }
    val segments = bounds.mapIndexed { i, b ->
      val end = bounds.getOrNull(i + 1)
      Segment(
        startByte = b.byte,
        endByte = end?.byte ?: data.size, // last segment includes any trailing tags (ID3v1)
        startSeconds = b.seconds,
        durationSeconds = (end?.seconds ?: seconds) - b.seconds,
      )
    }
    return Scan(leadingBytes = firstFrame, segments = segments, totalDurationSeconds = seconds)
  }

  /** Byte offset of the first mp3 frame (skipping any ID3v2 header). */
  internal fun audioStart(data: ByteArray): Int = findNextFrame(data, id3v2Size(data)) ?: data.size

  /** Walks the frames tiling [from, until); [from] must be at (or before) a real frame start. */
  internal fun frames(data: ByteArray, from: Int, until: Int): List<Frame> {
    val frames = mutableListOf<Frame>()
    var pos = from
    while (pos + FRAME_HEADER_SIZE <= until) {
      val frame = parseFrameHeader(data, pos)
      if (frame == null) {
        pos++
        continue
      }
      if (pos + frame.lengthBytes > until) break // truncated final frame
      frames += Frame(startByte = pos, endByte = pos + frame.lengthBytes, durationSeconds = frame.durationSeconds)
      pos += frame.lengthBytes
    }
    return frames
  }

  private data class Boundary(val byte: Int, val seconds: Double)

  private data class FrameInfo(val lengthBytes: Int, val durationSeconds: Double)

  private fun findNextFrame(data: ByteArray, from: Int): Int? {
    var pos = from
    while (pos + FRAME_HEADER_SIZE <= data.size) {
      if (parseFrameHeader(data, pos) != null) return pos
      pos++
    }
    return null
  }

  private fun parseFrameHeader(data: ByteArray, pos: Int): FrameInfo? {
    val b1 = data[pos].toInt() and 0xFF
    val b2 = data[pos + 1].toInt() and 0xFF
    val b3 = data[pos + 2].toInt() and 0xFF
    if (b1 != 0xFF || (b2 and 0xE0) != 0xE0) return null

    val version = (b2 shr 3) and 3 // 0=MPEG2.5, 1=reserved, 2=MPEG2, 3=MPEG1
    val layer = (b2 shr 1) and 3 // 1=Layer III
    if (version == 1 || layer != 1) return null

    val bitrateIndex = (b3 shr 4) and 15
    val sampleRateIndex = (b3 shr 2) and 3
    val padding = (b3 shr 1) and 1
    if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

    val mpeg1 = version == 3
    val bitrate = (if (mpeg1) MPEG1_BITRATES_KBPS else MPEG2_BITRATES_KBPS)[bitrateIndex] * 1000
    val sampleRate = SAMPLE_RATES.getValue(version)[sampleRateIndex]
    val samplesPerFrame = if (mpeg1) 1152 else 576
    return FrameInfo(
      lengthBytes = samplesPerFrame / 8 * bitrate / sampleRate + padding,
      durationSeconds = samplesPerFrame.toDouble() / sampleRate,
    )
  }

  private fun isTagFrame(data: ByteArray, pos: Int, frameLength: Int): Boolean {
    val bodyStart = pos + FRAME_HEADER_SIZE
    val bodyEnd = pos + frameLength
    val searchEnd = minOf(bodyStart + TAG_SEARCH_WINDOW, bodyEnd)
    val hasTag = TAG_STRINGS.any { data.contains(it, from = bodyStart, until = searchEnd) }
    if (!hasTag) return false

    var fillBytes = 0
    for (i in bodyStart until bodyEnd) {
      when (data[i]) {
        0x00.toByte(), 0x55.toByte(), 0xAA.toByte() -> fillBytes++
      }
    }
    return fillBytes.toDouble() / (bodyEnd - bodyStart) > MIN_TAG_FRAME_FILL_FRACTION
  }

  private fun id3v2Size(data: ByteArray): Int {
    if (data.size < 10 || data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return 0
    val size = (data[6].toInt() and 0x7F shl 21) or
      (data[7].toInt() and 0x7F shl 14) or
      (data[8].toInt() and 0x7F shl 7) or
      (data[9].toInt() and 0x7F)
    return 10 + size
  }

  private fun ByteArray.contains(needle: ByteArray, from: Int, until: Int): Boolean {
    outer@ for (i in from until until - needle.size + 1) {
      for (j in needle.indices) {
        if (this[i + j] != needle[j]) continue@outer
      }
      return true
    }
    return false
  }

  private const val FRAME_HEADER_SIZE = 4
  private const val TAG_SEARCH_WINDOW = 200
  private const val TRAILING_FLUSH_MERGE_SECONDS = 2.0
  private const val MIN_TAG_FRAME_FILL_FRACTION = 0.10
  private val TAG_STRINGS = listOf("Xing", "Info", "LAME", "VBRI").map { it.encodeToByteArray() }
  private val MPEG1_BITRATES_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
  private val MPEG2_BITRATES_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
  private val SAMPLE_RATES = mapOf(
    3 to intArrayOf(44100, 48000, 32000), // MPEG1
    2 to intArrayOf(22050, 24000, 16000), // MPEG2
    0 to intArrayOf(11025, 12000, 8000), // MPEG2.5
  )
}
