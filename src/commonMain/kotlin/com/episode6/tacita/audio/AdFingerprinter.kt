package com.episode6.tacita.audio

import com.episode6.tacita.AdFingerprintInfo
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.IOException

/**
 * A stored byte-level fingerprint of one confirmed/proven ad creative (or whole ad break):
 * the rolling hash + SHA-256 digest of each aligned [RollingHash.BLOCK_BYTES] block of the
 * creative's bytes. Byte-level matching is exact by design — the 2026-07-19 research
 * (docs/ALGORITHM.md) showed stitching hosts serve repeated creatives byte-identically
 * within their reuse scope, and that show content (fresh whole-episode encodes) essentially
 * never byte-repeats, so an exact match is ad-shaped evidence and a re-encoded creative is
 * simply a miss (the acceptable failure direction).
 */
internal class StoredAdFingerprint(
  val provenance: AdFingerprintInfo.Provenance,
  val durationMs: Long,
  val totalBytes: Long,
  val blockHashes: LongArray,
  val blockDigests: List<ByteString>,
) {
  init {
    require(blockHashes.size == blockDigests.size) { "block hash/digest count mismatch" }
  }

  /** Content-derived identity: stable across servings, independent of provenance. */
  val id: String by lazy {
    blockDigests.fold(okio.Buffer()) { buf, digest -> buf.also { it.write(digest) } }.sha256().hex()
  }

  val info: AdFingerprintInfo
    get() = AdFingerprintInfo(id = id, provenance = provenance, durationMs = durationMs, sizeBytes = totalBytes)

  override fun equals(other: Any?): Boolean = other is StoredAdFingerprint && other.id == id && other.provenance == provenance
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "StoredAdFingerprint(id=${id.take(12)}, provenance=$provenance, durationMs=$durationMs)"
}

/**
 * Extracts [StoredAdFingerprint]s from creative byte ranges and finds their recurrences in
 * later files. Matching is content-addressed (a creative is found wherever it appears,
 * never by slot position — creatives were observed rotating between pre/mid/post-roll
 * slots) and streams the file in fixed-size chunks, so peak memory is independent of
 * episode size (the mobile OOM lesson, docs/ALGORITHM.md 2026-07-05).
 */
internal class AdFingerprinter {

  /**
   * Fingerprints `data[fromByte, toByte)`, or returns null when the range is outside
   * [MIN_FINGERPRINT_SECONDS]..[MAX_FINGERPRINT_SECONDS] or spans less than one whole
   * block. The minimum length floor is load-bearing: the only byte-identical cross-episode
   * *content* observed in the field (stitched-in outro stubs) sits far below it.
   */
  fun extract(
    data: ByteArray,
    fromByte: Int,
    toByte: Int,
    durationSeconds: Double,
    provenance: AdFingerprintInfo.Provenance,
  ): StoredAdFingerprint? {
    if (durationSeconds < MIN_FINGERPRINT_SECONDS || durationSeconds > MAX_FINGERPRINT_SECONDS) return null
    val totalBytes = toByte - fromByte
    val blockCount = totalBytes / RollingHash.BLOCK_BYTES
    if (blockCount < 1) return null
    val hashes = LongArray(blockCount)
    val digests = ArrayList<ByteString>(blockCount)
    for (k in 0 until blockCount) {
      val start = fromByte + k * RollingHash.BLOCK_BYTES
      hashes[k] = RollingHash.hash(data, start)
      digests += data.toByteString(start, RollingHash.BLOCK_BYTES).sha256()
    }
    return StoredAdFingerprint(
      provenance = provenance,
      durationMs = (durationSeconds * 1000).toLong(),
      totalBytes = totalBytes.toLong(),
      blockHashes = hashes,
      blockDigests = digests,
    )
  }

  /** A verified recurrence of a stored creative inside a file. Byte range is frame-agnostic. */
  data class Match(
    val fingerprint: StoredAdFingerprint,
    val fromByte: Int,
    val toByte: Int,
    val matchedSeconds: Double,
  )

  /**
   * Finds recurrences of [fingerprints] in the file behind [handle]: a single streaming
   * rolling-hash pass collects candidate alignments (a stored block's hash seen at any
   * byte offset), then each alignment is verified block-by-block against the SHA-256
   * digests and the longest contiguous verified run is kept if it covers at least
   * [MIN_MATCH_SECONDS]. Edge slop in a stored fingerprint (a human-confirmed range that
   * included episode-unique bytes) only costs the unmatchable edge blocks — the interior
   * still aligns and verifies.
   */
  fun match(handle: FileHandle, fingerprints: List<StoredAdFingerprint>): List<Match> {
    if (fingerprints.isEmpty()) return emptyList()
    val fileSize = handle.size()
    if (fileSize > Int.MAX_VALUE) throw IOException("file too large to fingerprint-match: $fileSize bytes")
    val size = fileSize.toInt()
    if (size < RollingHash.BLOCK_BYTES) return emptyList()

    val lookup = HashMap<Long, MutableList<IntArray>>() // block hash -> [fingerprintIndex, blockIndex]
    fingerprints.forEachIndexed { fi, fp ->
      fp.blockHashes.forEachIndexed { bi, h -> lookup.getOrPut(h) { mutableListOf() }.add(intArrayOf(fi, bi)) }
    }

    // candidate creative-start offsets per fingerprint, found by the streaming scan
    val alignments = HashSet<Long>() // (fingerprintIndex shl 40) or startByte
    val ring = ByteArray(RollingHash.BLOCK_BYTES)
    var h = 0L
    var p = 0
    val chunk = ByteArray(SCAN_CHUNK_BYTES)
    var readAt = 0L
    while (readAt < size) {
      val n = handle.read(readAt, chunk, 0, minOf(chunk.size, (size - readAt).toInt()))
      if (n <= 0) break
      for (ci in 0 until n) {
        val b = chunk[ci]
        if (p < RollingHash.BLOCK_BYTES) {
          h = RollingHash.prime(h, b)
        } else {
          h = RollingHash.roll(h, out = ring[p % RollingHash.BLOCK_BYTES], inp = b)
        }
        ring[p % RollingHash.BLOCK_BYTES] = b
        if (p >= RollingHash.BLOCK_BYTES - 1) {
          val windowStart = p - RollingHash.BLOCK_BYTES + 1
          lookup[h]?.forEach { (fi, bi) ->
            val start = windowStart - bi * RollingHash.BLOCK_BYTES
            if (start >= 0 && alignments.size < MAX_CANDIDATE_ALIGNMENTS) {
              alignments += (fi.toLong() shl 40) or start.toLong()
            }
          }
        }
        p++
      }
      readAt += n
    }

    val matches = mutableListOf<Match>()
    val blockBuf = ByteArray(RollingHash.BLOCK_BYTES)
    for (key in alignments) {
      val fi = (key shr 40).toInt()
      val start = (key and 0xFF_FFFF_FFFFL).toInt()
      val fp = fingerprints[fi]
      var runStart = -1
      var bestStart = -1
      var bestLen = 0
      for (k in 0..fp.blockDigests.size) {
        val verified = k < fp.blockDigests.size && verifyBlock(handle, size, start + k * RollingHash.BLOCK_BYTES, blockBuf, fp.blockDigests[k])
        if (verified) {
          if (runStart < 0) runStart = k
        } else if (runStart >= 0) {
          if (k - runStart > bestLen) {
            bestLen = k - runStart
            bestStart = runStart
          }
          runStart = -1
        }
      }
      if (bestLen == 0) continue
      val bytesPerSecond = fp.totalBytes * 1000.0 / fp.durationMs
      val matchedSeconds = bestLen * RollingHash.BLOCK_BYTES / bytesPerSecond
      if (matchedSeconds < MIN_MATCH_SECONDS) continue
      matches += Match(
        fingerprint = fp,
        fromByte = start + bestStart * RollingHash.BLOCK_BYTES,
        toByte = start + (bestStart + bestLen) * RollingHash.BLOCK_BYTES,
        matchedSeconds = matchedSeconds,
      )
    }

    // overlapping alignments of the same creative (e.g. a break that internally repeats a
    // creative) resolve to the strongest span; distinct creatives may legitimately overlap
    val kept = mutableListOf<Match>()
    matches.sortedWith(compareByDescending<Match> { it.matchedSeconds }.thenBy { it.fromByte }).forEach { m ->
      val overlapsKept = kept.any {
        it.fingerprint.id == m.fingerprint.id && it.fromByte < m.toByte && m.fromByte < it.toByte
      }
      if (!overlapsKept) kept += m
    }
    return kept.sortedBy { it.fromByte }
  }

  private fun verifyBlock(handle: FileHandle, fileSize: Int, at: Int, buf: ByteArray, expected: ByteString): Boolean {
    if (at < 0 || at + buf.size > fileSize) return false
    var filled = 0
    while (filled < buf.size) {
      val read = handle.read((at + filled).toLong(), buf, filled, buf.size - filled)
      if (read <= 0) return false
      filled += read
    }
    return buf.toByteString(0, buf.size).sha256() == expected
  }

  companion object {
    /**
     * Floors/ceilings on what may be fingerprinted and reported. The 5s floors sit far
     * above the largest observed byte-identical cross-episode content (≤0.9s outro stubs,
     * docs/ALGORITHM.md 2026-07-19) and below the shortest observed creative (~7s).
     */
    const val MIN_FINGERPRINT_SECONDS = 5.0
    const val MAX_FINGERPRINT_SECONDS = 600.0
    const val MIN_MATCH_SECONDS = 5.0

    private const val SCAN_CHUNK_BYTES = 1 shl 16
    private const val MAX_CANDIDATE_ALIGNMENTS = 4096
  }
}
