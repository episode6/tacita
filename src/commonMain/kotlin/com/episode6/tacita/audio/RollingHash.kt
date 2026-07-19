package com.episode6.tacita.audio

/**
 * The polynomial rolling hash shared by [AdCutter]'s anchor index and [AdFingerprinter]'s
 * block matching. The window size is fixed at [BLOCK_BYTES] so the roll-out power can be
 * precomputed once; both users index 4KB-aligned blocks and scan with a per-byte roll.
 */
internal object RollingHash {
  /** Window/block size. Shared runs at least twice this long are always found by a scan. */
  const val BLOCK_BYTES = 4096

  private const val HASH_BASE = 1_000_003L
  private val HASH_POW = run {
    var p = 1L
    repeat(BLOCK_BYTES - 1) { p *= HASH_BASE }
    p
  }

  /** Hash of the [BLOCK_BYTES]-long window starting at [from]. */
  fun hash(bytes: ByteArray, from: Int): Long {
    var h = 0L
    for (k in from until from + BLOCK_BYTES) {
      h = h * HASH_BASE + (bytes[k].toLong() and 0xFF)
    }
    return h
  }

  /** Slides a window hash forward one byte: [out] leaves the window, [inp] enters it. */
  fun roll(h: Long, out: Byte, inp: Byte): Long =
    (h - (out.toLong() and 0xFF) * HASH_POW) * HASH_BASE + (inp.toLong() and 0xFF)

  /** One step of a priming pass (window not yet full). */
  fun prime(h: Long, inp: Byte): Long = h * HASH_BASE + (inp.toLong() and 0xFF)
}
