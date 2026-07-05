package com.episode6.tacita.audio

import com.episode6.tacita.systemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Removes dynamically-injected ads from a downloaded mp3 by diffing it against a second
 * copy of the same episode downloaded the same way. Dynamic ad insertion varies per
 * request while everything the episode always ships with — content and any baked-in
 * material — is served as the same encoded bytes, so byte runs present in both copies
 * are kept and byte runs unique to the primary copy are the injected ads. Identical
 * copies mean nothing was injected.
 *
 * Alignment is content-based, rsync-style: fixed-size blocks of the primary are indexed
 * by rolling hash, and after each divergence the reference is scanned forward for the
 * next shared block, which is then walked backwards to the exact splice point. No stitch
 * markers are needed, so hosts that insert ads without tag frames (e.g. Audioboom) work
 * the same as hosts that leave them (e.g. Acast). Cut edges are snapped to mp3 frame
 * boundaries and the splice is lossless — no re-encoding.
 *
 * If the copies don't largely agree (or a safety check fails) the file is left
 * untouched. An ad served identically in both copies survives: the failure mode is
 * always keeping too much, never cutting material that belongs to the episode.
 */
internal class AdCutter(
  private val fileSystem: FileSystem = systemFileSystem,
  private val log: (String) -> Unit = {},
  private val mp3SegmentParser: Mp3SegmentParser = Mp3SegmentParser(),
  private val id3ChapterShifter: Id3ChapterShifter = Id3ChapterShifter(),
) {

  data class Config(
    /** Never remove more than this fraction of the total duration. */
    val maxCutFraction: Double = 0.25,
    /** The two copies must agree on at least this fraction of the primary copy's duration. */
    val minMatchFraction: Double = 0.5,
  )

  sealed class Result {
    object NoAdsFound : Result()

    data class AdsCut(
      val adBreaksRemoved: Int,
      val secondsRemoved: Double,
      /** The removed ranges, in the pre-cut file's timeline; sorted and non-overlapping. */
      val cuts: List<Cut>,
    ) : Result() {
      // the cut list is payload for the ad-boundary pass, not for the diagnostic log line
      override fun toString(): String = "AdsCut(adBreaksRemoved=$adBreaksRemoved, secondsRemoved=$secondsRemoved)"
    }

    data class Skipped(
      val reason: String,
      /** The ranges the guards refused to cut (file untouched); empty when no diff ran. */
      val cuts: List<Cut> = emptyList(),
    ) : Result() {
      override fun toString(): String = "Skipped(reason=$reason)"
    }
  }

  suspend fun cutAds(file: Path, referenceFile: Path, config: Config = Config()): Result =
    withContext(Dispatchers.IO) {
      val result = cut(file, referenceFile, config)
      log("AdCutter: ${file.name}: $result")
      result
    }

  private fun cut(file: Path, referenceFile: Path, config: Config): Result {
    val data = fileSystem.read(file) { readByteArray() }
    val reference = fileSystem.read(referenceFile) { readByteArray() }
    if (data.contentEquals(reference)) return Result.NoAdsFound

    val dataStart = mp3SegmentParser.audioStart(data)
    val frames = mp3SegmentParser.frames(data, from = dataStart, until = data.size)
    if (frames.isEmpty()) return Result.Skipped("no mp3 frames found")
    val totalSeconds = frames.sumOf { it.durationSeconds }

    val rawCuts = diff(data, dataStart, reference, mp3SegmentParser.audioStart(reference))
    val cuts = snapToFrames(rawCuts, frames)
    val secondsRemoved = cuts.sumOf { it.seconds }

    if (totalSeconds - secondsRemoved < totalSeconds * config.minMatchFraction) {
      return Result.Skipped(
        "copies only agree on ${(totalSeconds - secondsRemoved).toInt()}s of ${totalSeconds.toInt()}s",
        cuts = cuts,
      )
    }
    if (cuts.isEmpty()) return Result.NoAdsFound
    if (secondsRemoved > totalSeconds * config.maxCutFraction) {
      return Result.Skipped(
        "would remove ${secondsRemoved.toInt()}s of ${totalSeconds.toInt()}s " +
          "(more than ${(config.maxCutFraction * 100).toInt()}%)",
        cuts = cuts,
      )
    }

    val leading = id3ChapterShifter.shift(
      id3 = data.copyOfRange(0, dataStart),
      cuts = cuts.map {
        Id3ChapterShifter.CutRange(
          fromByte = it.fromByte,
          toByte = it.toByte,
          fromMs = (it.fromSeconds * 1000).toLong(),
          toMs = (it.toSeconds * 1000).toLong(),
        )
      },
    )
    val tempFile = "$file.adcut.tmp".toPath()
    try {
      fileSystem.write(tempFile) {
        write(leading)
        var pos = dataStart
        cuts.forEach { cut ->
          write(data, pos, cut.fromByte - pos)
          pos = cut.toByte
        }
        write(data, pos, data.size - pos)
      }
      fileSystem.atomicMove(tempFile, file)
    } finally {
      fileSystem.delete(tempFile, mustExist = false)
    }
    return Result.AdsCut(adBreaksRemoved = cuts.size, secondsRemoved = secondsRemoved, cuts = cuts)
  }

  /**
   * Byte ranges of the primary copy that are not present in the reference copy: walk both
   * in lockstep while they agree; at each divergence find the next shared anchor and cut
   * whatever the primary held in between.
   */
  private fun diff(data: ByteArray, dataStart: Int, reference: ByteArray, referenceStart: Int): List<Pair<Int, Int>> {
    val anchors = AnchorIndex(data, dataStart)
    val cuts = mutableListOf<Pair<Int, Int>>()
    var i = dataStart
    var j = referenceStart
    while (i < data.size && j < reference.size) {
      if (data[i] == reference[j]) {
        i++
        j++
        continue
      }
      val anchor = anchors.findRealignment(data, i, dataStart, reference, j, referenceStart)
        ?: return cuts.also { it += i to data.size }
      var (pi, rj) = anchor
      if (pi < i) { // the shared run started before the walk diverged; slide forward along it
        rj += i - pi
        pi = i
      }
      if (pi > i) cuts += i to pi
      i = pi
      j = rj
    }
    if (i < data.size) cuts += i to data.size
    return cuts
  }

  /** A frame-snapped range unique to the primary copy, in the pre-cut file's timeline. */
  data class Cut(
    val fromByte: Int,
    val toByte: Int,
    val fromSeconds: Double,
    val toSeconds: Double,
  ) {
    val seconds: Double get() = toSeconds - fromSeconds
  }

  /**
   * Aligns raw cut edges outward to frame boundaries — a divergence can land mid-frame
   * (frame headers match across encodes) and a splice must not keep partial frames.
   */
  private fun snapToFrames(rawCuts: List<Pair<Int, Int>>, frames: List<Mp3SegmentParser.Frame>): List<Cut> {
    if (rawCuts.isEmpty()) return emptyList()
    val starts = IntArray(frames.size) { frames[it].startByte }
    val prefixSeconds = DoubleArray(frames.size + 1)
    frames.forEachIndexed { i, f -> prefixSeconds[i + 1] = prefixSeconds[i] + f.durationSeconds }

    fun floorIndex(byte: Int): Int {
      val i = binarySearch(starts, byte)
      return if (i >= 0) i else -i - 2
    }

    fun ceilIndex(byte: Int): Int {
      val i = binarySearch(starts, byte)
      return if (i >= 0) i else -i - 1
    }

    val merged = mutableListOf<IntArray>() // [fromFrame, toFrame(exclusive)] with byte fallbacks handled after
    for ((a, b) in rawCuts) {
      val fi = floorIndex(a).coerceAtLeast(0)
      val from = if (a >= frames[fi].endByte) a else starts[fi] // past the last frame: trailing tags, keep raw edge
      val ci = ceilIndex(b)
      val to = when {
        ci < frames.size          -> starts[ci]
        b >= frames.last().endByte -> b
        else                      -> frames.last().endByte
      }
      val fromIdx = ceilIndex(from)
      if (merged.isNotEmpty() && from <= merged.last()[1]) {
        merged.last()[1] = maxOf(merged.last()[1], to)
        merged.last()[3] = maxOf(merged.last()[3], ci)
      } else {
        merged += intArrayOf(from, to, fromIdx, ci)
      }
    }
    return merged.map { (from, to, fromIdx, toIdx) ->
      Cut(
        fromByte = from,
        toByte = to,
        fromSeconds = prefixSeconds[fromIdx],
        toSeconds = prefixSeconds[toIdx.coerceAtMost(frames.size)],
      )
    }
  }

  /**
   * Rolling-hash index of the primary copy's aligned [ANCHOR_BYTES] blocks. Realignment
   * scans the reference forward from a divergence until one of its windows matches an
   * indexed block, then extends the match backwards to the point where the copies
   * actually rejoin. Scanning continues a little past the first hit so a creative that
   * also appears later in the primary can't win over a nearer rejoin point.
   */
  private class AnchorIndex(private val data: ByteArray, dataStart: Int) {
    private val positions = HashMap<Long, MutableList<Int>>()

    init {
      var pos = dataStart
      while (pos + ANCHOR_BYTES <= data.size) {
        positions.getOrPut(hash(data, pos)) { mutableListOf() }.add(pos)
        pos += ANCHOR_BYTES
      }
    }

    fun findRealignment(
      data: ByteArray,
      i: Int,
      dataStart: Int,
      reference: ByteArray,
      j: Int,
      referenceStart: Int,
    ): Pair<Int, Int>? {
      if (j + ANCHOR_BYTES > reference.size) return null
      var h = hash(reference, j)
      var dj = 0
      var firstHitAt = -1
      var best: Pair<Int, Int>? = null
      while (true) {
        for (candidate in positions[h].orEmpty()) {
          if (candidate < i) continue
          if (!regionsEqual(data, candidate, reference, j + dj)) continue
          var pi = candidate
          var rj = j + dj
          while (pi > dataStart && rj > referenceStart && data[pi - 1] == reference[rj - 1]) {
            pi--
            rj--
          }
          if (best == null || pi < best.first) best = pi to rj
          if (firstHitAt < 0) firstHitAt = dj
          if (pi <= i) return best // nothing unique to the primary here; can't do better
        }
        if (firstHitAt >= 0 && dj - firstHitAt > REALIGN_LOOKAHEAD_BYTES) break
        if (j + dj + ANCHOR_BYTES >= reference.size) break
        h = roll(h, out = reference[j + dj], inp = reference[j + dj + ANCHOR_BYTES])
        dj++
      }
      return best
    }

    private fun regionsEqual(data: ByteArray, dataFrom: Int, reference: ByteArray, referenceFrom: Int): Boolean {
      if (referenceFrom + ANCHOR_BYTES > reference.size) return false
      for (k in 0 until ANCHOR_BYTES) {
        if (data[dataFrom + k] != reference[referenceFrom + k]) return false
      }
      return true
    }

    private fun hash(bytes: ByteArray, from: Int): Long {
      var h = 0L
      for (k in from until from + ANCHOR_BYTES) {
        h = h * HASH_BASE + (bytes[k].toLong() and 0xFF)
      }
      return h
    }

    private fun roll(h: Long, out: Byte, inp: Byte): Long =
      (h - (out.toLong() and 0xFF) * HASH_POW) * HASH_BASE + (inp.toLong() and 0xFF)

    private companion object {
      const val HASH_BASE = 1_000_003L
      val HASH_POW = run {
        var p = 1L
        repeat(ANCHOR_BYTES - 1) { p *= HASH_BASE }
        p
      }
    }
  }

  private companion object {
    /** Anchor block size: shared runs at least twice this long are always found. */
    const val ANCHOR_BYTES = 4096

    /** How far past the first realignment hit to keep looking for a nearer rejoin point. */
    const val REALIGN_LOOKAHEAD_BYTES = 4 * 1024 * 1024

    /** Same contract as java.util.Arrays.binarySearch: index, or -(insertionPoint + 1). */
    fun binarySearch(array: IntArray, value: Int): Int {
      var low = 0
      var high = array.size - 1
      while (low <= high) {
        val mid = (low + high) ushr 1
        when {
          array[mid] < value -> low = mid + 1
          array[mid] > value -> high = mid - 1
          else               -> return mid
        }
      }
      return -(low + 1)
    }
  }
}
