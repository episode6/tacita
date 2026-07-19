package com.episode6.tacita.audio

import okio.Buffer
import okio.FileHandle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A level-invariant acoustic fingerprint of one ad creative: spectral-peak constellation
 * landmarks (anchor-peak/target-peak pair hashes with their anchor times) computed from
 * decoded PCM. Unlike [StoredAdFingerprint]'s byte layer, matching survives re-encoding,
 * resampling and gain normalization — the Simplecast-class case where every episode is a
 * fresh gain-normalized transcode and byte identity never holds (docs/ALGORITHM.md
 * 2026-07-19).
 *
 * [hashes]`[i]`/[frames]`[i]` are parallel: a 24-bit landmark hash (anchor bin, target bin,
 * frame delta) and the STFT frame index of its anchor, sorted by anchor frame.
 */
internal class AcousticFingerprint(
  val durationMs: Long,
  val hashes: IntArray,
  val frames: IntArray,
) {
  init {
    require(hashes.size == frames.size) { "landmark hash/frame count mismatch" }
  }

  /** Content-derived identity: stable for the same decoded audio, independent of position. */
  val id: String by lazy {
    Buffer().apply {
      hashes.forEach { writeInt(it) }
      frames.forEach { writeInt(it) }
    }.sha256().hex()
  }

  override fun equals(other: Any?): Boolean = other is AcousticFingerprint && other.id == id
  override fun hashCode(): Int = id.hashCode()
  override fun toString(): String = "AcousticFingerprint(id=${id.take(12)}, durationMs=$durationMs, landmarks=${hashes.size})"
}

/**
 * Extracts [AcousticFingerprint]s from a time range of an mp3 file and finds their
 * recurrences in other files. The pipeline (all common code, no platform DSP):
 * streaming [Mp3Decoder] frames → mono downmix → linear resample to [TARGET_HZ] →
 * Hann-windowed STFT ([FFT_SIZE]/[HOP_SIZE] via [Fft]) → spectral peaks (strict local
 * maxima over a small time×frequency neighborhood, thresholded *relative* to the frame's
 * geometric-mean power so peak positions are gain-invariant) → Shazam-style landmark
 * hashes (each anchor peak paired with the next few peaks ahead of it).
 *
 * Matching votes each shared hash into a per-fingerprint histogram of time offsets
 * (query anchor frame minus fingerprint anchor frame): a true occurrence puts many votes
 * on one offset, while coincidental hash collisions scatter. Like the byte layer, files
 * are processed in streaming fashion — peak memory is bounded by the landmark lists, never
 * by episode PCM (the mobile OOM lesson, docs/ALGORITHM.md 2026-07-05).
 */
internal class AcousticFingerprinter {

  /**
   * Fingerprints the audio decoded from `[fromMs, toMs)` of the file, or returns null when
   * the decoded range is outside [MIN_FINGERPRINT_SECONDS]..[MAX_FINGERPRINT_SECONDS] or
   * yields fewer than [MIN_FINGERPRINT_LANDMARKS] landmarks over
   * [MIN_FINGERPRINT_DISTINCT_HASHES] distinct hashes. Both floors reject audio with no
   * constellation worth matching: silence produces no landmarks at all, and stationary
   * audio (a held tone) produces many copies of a handful of hashes — a degenerate
   * fingerprint that would "match" any other occurrence of near-stationary audio.
   */
  fun extract(handle: FileHandle, fromMs: Long = 0, toMs: Long = Long.MAX_VALUE): AcousticFingerprint? {
    val session = AcousticLandmarkSession()
    decodeInto(handle, fromMs, toMs, session)
    return finishExtract(session)
  }

  /**
   * Fingerprints the audio decoded from `data[fromByte, toByte)` — a creative's mp3 bytes
   * already in memory (e.g. a diff-cut range extracted from the pre-cut file before its
   * bytes are discarded). Same floors as the [FileHandle] overload. When the range starts
   * mid-segment the decoder skips frames until the bit reservoir primes, losing a fraction
   * of a second at the edge — tolerated the same way edge slop is everywhere else.
   */
  fun extract(data: ByteArray, fromByte: Int, toByte: Int): AcousticFingerprint? {
    val session = AcousticLandmarkSession()
    val decoder = Mp3Decoder()
    val pcm = FloatArray(Mp3Decoder.MAX_SAMPLES_PER_FRAME)
    var at = fromByte
    while (at < toByte) {
      val samples = decoder.decodeFrame(data, at, toByte - at, pcm)
      if (samples > 0) session.feed(pcm, 0, samples, decoder.info.channels, decoder.info.hz)
      if (decoder.info.frameBytes == 0) break
      at += decoder.info.frameBytes
    }
    return finishExtract(session)
  }

  private fun finishExtract(session: AcousticLandmarkSession): AcousticFingerprint? {
    val landmarks = session.finish()
    if (landmarks.durationMs < MIN_FINGERPRINT_SECONDS * 1000) return null
    if (landmarks.durationMs > MAX_FINGERPRINT_SECONDS * 1000) return null
    if (landmarks.hashes.size < MIN_FINGERPRINT_LANDMARKS) return null
    if (landmarks.hashes.toHashSet().size < MIN_FINGERPRINT_DISTINCT_HASHES) return null
    return AcousticFingerprint(durationMs = landmarks.durationMs, hashes = landmarks.hashes, frames = landmarks.frames)
  }

  /** A probable recurrence of a stored creative. Times are in the matched file's timeline. */
  data class Match(
    val fingerprint: AcousticFingerprint,
    val fromMs: Long,
    val toMs: Long,
    val matchedLandmarks: Int,
    val matchedSeconds: Double,
  )

  /**
   * Finds recurrences of [fingerprints] in the file behind [handle]. A match requires
   * [MIN_MATCH_LANDMARKS] landmark hashes agreeing on one time offset (±1 frame) *and* the
   * agreeing anchors spanning at least [MIN_MATCH_SECONDS] — coincidental collisions fail
   * the offset consensus, a re-encoded creative keeps it (enough peaks survive transcodes;
   * see AcousticFingerprinterTest for the pinned cross-encode evidence).
   */
  fun match(handle: FileHandle, fingerprints: List<AcousticFingerprint>): List<Match> {
    if (fingerprints.isEmpty()) return emptyList()
    val session = AcousticLandmarkSession()
    decodeInto(handle, 0, Long.MAX_VALUE, session)
    val query = session.finish()
    if (query.hashes.isEmpty()) return emptyList()

    val lookup = HashMap<Int, MutableList<Long>>() // hash -> (fingerprintIndex shl 32) or anchorFrame
    fingerprints.forEachIndexed { fi, fp ->
      fp.hashes.forEachIndexed { i, h ->
        lookup.getOrPut(h) { mutableListOf() }.add((fi.toLong() shl 32) or fp.frames[i].toLong())
      }
    }

    // one vote per shared hash, keyed by (fingerprint, offset); OFFSET_BIAS keeps negative
    // offsets (a creative straddling the file start) in non-negative key space
    val votes = HashMap<Long, Int>()
    for (qi in query.hashes.indices) {
      lookup[query.hashes[qi]]?.forEach { packed ->
        val offset = query.frames[qi] - (packed and 0xFFFF_FFFFL).toInt()
        val key = (packed and 0xFFFF_FFFFL.inv()) or (offset + OFFSET_BIAS).toLong()
        votes[key] = (votes[key] ?: 0) + 1
      }
    }

    // qualifying offsets: ±1-frame-smoothed vote count over the floor, and a local maximum
    // so one occurrence doesn't report as three adjacent offsets
    val winners = HashMap<Long, IntArray>() // key -> [matchedLandmarks, minQueryFrame, maxQueryFrame]
    votes.forEach { (key, count) ->
      val smoothed = count + (votes[key - 1] ?: 0) + (votes[key + 1] ?: 0)
      if (smoothed < MIN_MATCH_LANDMARKS) return@forEach
      if (count < (votes[key - 1] ?: 0) || count < (votes[key + 1] ?: 0)) return@forEach
      winners[key] = intArrayOf(0, Int.MAX_VALUE, Int.MIN_VALUE)
    }
    if (winners.isEmpty()) return emptyList()

    // second pass: accumulate each winner's matched span from its supporting anchors
    for (qi in query.hashes.indices) {
      lookup[query.hashes[qi]]?.forEach { packed ->
        val qFrame = query.frames[qi]
        val offset = qFrame - (packed and 0xFFFF_FFFFL).toInt()
        val key = (packed and 0xFFFF_FFFFL.inv()) or (offset + OFFSET_BIAS).toLong()
        for (delta in -1..1) {
          winners[key + delta]?.let { acc ->
            acc[0]++
            if (qFrame < acc[1]) acc[1] = qFrame
            if (qFrame > acc[2]) acc[2] = qFrame
          }
        }
      }
    }

    val matches = winners.mapNotNull { (key, acc) ->
      val matchedSeconds = (acc[2] - acc[1] + 1) * SECONDS_PER_FRAME
      if (matchedSeconds < MIN_MATCH_SECONDS) return@mapNotNull null
      Match(
        fingerprint = fingerprints[(key shr 32).toInt()],
        fromMs = (acc[1] * SECONDS_PER_FRAME * 1000).toLong(),
        toMs = ((acc[2] + 1) * SECONDS_PER_FRAME * 1000).toLong(),
        matchedLandmarks = acc[0],
        matchedSeconds = matchedSeconds,
      )
    }

    // adjacent qualifying offsets of the same occurrence overlap almost entirely; resolve
    // per-fingerprint overlaps to the strongest span (distinct creatives may overlap)
    val kept = mutableListOf<Match>()
    matches.sortedWith(compareByDescending<Match> { it.matchedLandmarks }.thenBy { it.fromMs }).forEach { m ->
      val overlapsKept = kept.any {
        it.fingerprint.id == m.fingerprint.id && it.fromMs < m.toMs && m.fromMs < it.toMs
      }
      if (!overlapsKept) kept += m
    }
    return kept.sortedBy { it.fromMs }
  }

  /**
   * Streams the file through [Mp3Decoder] and feeds the decoded samples overlapping
   * `[fromMs, toMs)` into [session]. Reads in [DECODE_BUFFER_BYTES] chunks with carry-over
   * compaction; the buffer always holds at least [DECODE_LOOKAHEAD_BYTES] ahead of the
   * decode position mid-file, so the decoder never sees a partial frame except at EOF.
   */
  private fun decodeInto(handle: FileHandle, fromMs: Long, toMs: Long, session: AcousticLandmarkSession) {
    val fileSize = handle.size()
    val decoder = Mp3Decoder()
    val buf = ByteArray(DECODE_BUFFER_BYTES)
    val pcm = FloatArray(Mp3Decoder.MAX_SAMPLES_PER_FRAME)
    val fromUs = fromMs * 1000
    val toUs = if (toMs > Long.MAX_VALUE / 1000) Long.MAX_VALUE else toMs * 1000
    var bufStart = 0
    var bufEnd = 0
    var fileAt = 0L
    var timeUs = 0L
    while (timeUs < toUs) {
      if (bufEnd - bufStart < DECODE_LOOKAHEAD_BYTES && fileAt < fileSize) {
        if (bufStart > 0) {
          buf.copyInto(buf, 0, bufStart, bufEnd)
          bufEnd -= bufStart
          bufStart = 0
        }
        while (bufEnd < buf.size && fileAt < fileSize) {
          val n = handle.read(fileAt, buf, bufEnd, buf.size - bufEnd)
          if (n <= 0) break
          bufEnd += n
          fileAt += n
        }
      }
      if (bufEnd == bufStart) break
      val samples = decoder.decodeFrame(buf, bufStart, bufEnd - bufStart, pcm)
      if (samples > 0) {
        val hz = decoder.info.hz
        val channels = decoder.info.channels
        val frameUs = samples * 1_000_000L / hz
        if (timeUs + frameUs > fromUs) {
          val skip = if (timeUs >= fromUs) 0 else ((fromUs - timeUs) * hz / 1_000_000L).toInt()
          val end = if (timeUs + frameUs <= toUs) samples else ((toUs - timeUs) * hz / 1_000_000L).toInt()
          if (end > skip) session.feed(pcm, skip * channels, end - skip, channels, hz)
        }
        timeUs += frameUs
      }
      if (decoder.info.frameBytes == 0) break
      bufStart += decoder.info.frameBytes
    }
  }

  companion object {
    /** Analysis rate: both common podcast rates (22.05/44.1kHz) resample exactly; 0-5.5kHz kept. */
    const val TARGET_HZ = 11025
    const val FFT_SIZE = 1024
    const val HOP_SIZE = 512
    const val SECONDS_PER_FRAME: Double = HOP_SIZE.toDouble() / TARGET_HZ

    /** Same floors as the byte layer ([AdFingerprinter]) — see docs/ALGORITHM.md 2026-07-19. */
    const val MIN_FINGERPRINT_SECONDS = 5.0
    const val MAX_FINGERPRINT_SECONDS = 600.0
    const val MIN_MATCH_SECONDS = 5.0

    const val MIN_FINGERPRINT_LANDMARKS = 64
    const val MIN_FINGERPRINT_DISTINCT_HASHES = 48
    const val MIN_MATCH_LANDMARKS = 20

    private const val OFFSET_BIAS = 1 shl 24
    private const val DECODE_BUFFER_BYTES = 256 * 1024
    private const val DECODE_LOOKAHEAD_BYTES = 64 * 1024
  }
}

/**
 * The streaming PCM → landmark pipeline behind [AcousticFingerprinter]: feed interleaved
 * float samples (any rate/channel count, may vary between calls), then [finish] to pair
 * the collected peaks into landmarks. Memory is bounded by one FFT window plus the peak
 * list — never the fed audio. Peaks in the first/last [PEAK_RADIUS_FRAMES] STFT frames are
 * not emitted (no full neighborhood); ~0.1s of edge loss per creative, accepted.
 */
internal class AcousticLandmarkSession {

  class Landmarks(val hashes: IntArray, val frames: IntArray, val durationMs: Long)

  // resampler state: srcIdx counts fed mono samples, nextOutPos is the source-position of
  // the next output sample, prev carries the previous mono sample across feed() calls
  private var srcIdx = 0L
  private var nextOutPos = 0.0
  private var prev = 0f
  private var lastHz = 0
  private var outSamples = 0L

  private val hann = FloatArray(AcousticFingerprinter.FFT_SIZE) {
    (0.5 - 0.5 * cos(2.0 * PI * it / AcousticFingerprinter.FFT_SIZE)).toFloat()
  }
  private val fft = Fft(AcousticFingerprinter.FFT_SIZE)
  private val window = FloatArray(AcousticFingerprinter.FFT_SIZE)
  private var windowFill = 0
  private val re = FloatArray(AcousticFingerprinter.FFT_SIZE)
  private val im = FloatArray(AcousticFingerprinter.FFT_SIZE)

  // ring of the last RING_FRAMES frames of log-power spectra (+ per-frame geometric mean
  // and maximum, both over bins >= MIN_BIN)
  private val ringLogs = Array(RING_FRAMES) { FloatArray(BINS) }
  private val ringMean = FloatArray(RING_FRAMES)
  private val ringMax = FloatArray(RING_FRAMES)
  private var frameCount = 0

  // packed peaks, (frame shl 10) or bin, appended in frame order
  private var peaks = IntArray(1024)
  private var peakCount = 0

  private val candidateBins = IntArray(BINS)
  private val keptBins = IntArray(MAX_PEAKS_PER_FRAME)

  /** Feeds `pcm[offset ..< offset + samplesPerChannel * channels]` (interleaved). */
  fun feed(pcm: FloatArray, offset: Int, samplesPerChannel: Int, channels: Int, hz: Int) {
    require(hz > 0 && channels > 0) { "invalid pcm format: hz=$hz channels=$channels" }
    if (lastHz != 0 && hz != lastHz) {
      // keep the pending fractional resample position continuous across a rate change
      nextOutPos = srcIdx + (nextOutPos - srcIdx) * hz / lastHz
    }
    lastHz = hz
    val step = hz.toDouble() / AcousticFingerprinter.TARGET_HZ
    for (s in 0 until samplesPerChannel) {
      var mono = 0f
      val base = offset + s * channels
      for (c in 0 until channels) mono += pcm[base + c]
      mono /= channels
      while (nextOutPos <= srcIdx) {
        val f = (nextOutPos - (srcIdx - 1)).toFloat()
        push(prev + (mono - prev) * f)
        nextOutPos += step
      }
      prev = mono
      srcIdx++
    }
  }

  fun finish(): Landmarks {
    val (hashes, frames) = pairLandmarks()
    return Landmarks(hashes = hashes, frames = frames, durationMs = outSamples * 1000 / AcousticFingerprinter.TARGET_HZ)
  }

  private fun push(sample: Float) {
    window[windowFill++] = sample
    outSamples++
    if (windowFill == AcousticFingerprinter.FFT_SIZE) {
      processFrame()
      window.copyInto(window, 0, AcousticFingerprinter.HOP_SIZE, AcousticFingerprinter.FFT_SIZE)
      windowFill = AcousticFingerprinter.FFT_SIZE - AcousticFingerprinter.HOP_SIZE
    }
  }

  private fun processFrame() {
    for (i in 0 until AcousticFingerprinter.FFT_SIZE) {
      re[i] = window[i] * hann[i]
      im[i] = 0f
    }
    fft.transform(re, im)
    val slot = frameCount % RING_FRAMES
    val logs = ringLogs[slot]
    var sum = 0.0
    var maxLog = Float.NEGATIVE_INFINITY
    for (k in 0 until BINS) {
      val l = ln(re[k] * re[k] + im[k] * im[k] + POWER_EPS)
      logs[k] = l
      if (k >= MIN_BIN) {
        sum += l
        if (l > maxLog) maxLog = l
      }
    }
    ringMean[slot] = (sum / (BINS - MIN_BIN)).toFloat()
    ringMax[slot] = maxLog
    frameCount++
    if (frameCount >= RING_FRAMES) emitPeaks(frameCount - 1 - PEAK_RADIUS_FRAMES)
  }

  /** Emits the strict local maxima of [center]'s spectrum (its full ±radius ring is present). */
  private fun emitPeaks(center: Int) {
    val logs = ringLogs[center % RING_FRAMES]
    val floor = max(
      ringMean[center % RING_FRAMES] + PEAK_PROMINENCE_NATS,
      ringMax[center % RING_FRAMES] - PEAK_DYNAMIC_RANGE_NATS,
    )
    var candidates = 0
    for (k in MIN_BIN until BINS) {
      val v = logs[k]
      if (v < floor) continue
      var isPeak = true
      var dt = -PEAK_RADIUS_FRAMES
      outer@ while (dt <= PEAK_RADIUS_FRAMES) {
        val neighborLogs = ringLogs[(center + dt) % RING_FRAMES]
        val lo = max(0, k - PEAK_RADIUS_BINS)
        val hi = min(BINS - 1, k + PEAK_RADIUS_BINS)
        for (nk in lo..hi) {
          if (dt == 0 && nk == k) continue
          if (neighborLogs[nk] >= v) {
            isPeak = false
            break@outer
          }
        }
        dt++
      }
      if (isPeak) candidateBins[candidates++] = k
    }
    // keep the strongest MAX_PEAKS_PER_FRAME candidates, appended in ascending-bin order
    var kept = 0
    for (ci in 0 until candidates) {
      val k = candidateBins[ci]
      if (kept < MAX_PEAKS_PER_FRAME) {
        keptBins[kept++] = k
      } else {
        var weakest = 0
        for (i in 1 until kept) if (logs[keptBins[i]] < logs[keptBins[weakest]]) weakest = i
        if (logs[k] > logs[keptBins[weakest]]) keptBins[weakest] = k
      }
    }
    keptBins.sort(0, kept)
    for (i in 0 until kept) appendPeak((center shl 10) or keptBins[i])
  }

  private fun appendPeak(packed: Int) {
    if (peakCount == peaks.size) peaks = peaks.copyOf(peaks.size * 2)
    peaks[peakCount++] = packed
  }

  /** Pairs each anchor peak with the next [PAIR_FANOUT] peaks within [MAX_PAIR_DT_FRAMES]. */
  private fun pairLandmarks(): Pair<IntArray, IntArray> {
    var hashes = IntArray(1024)
    var frames = IntArray(1024)
    var count = 0
    for (i in 0 until peakCount) {
      val anchorFrame = peaks[i] ushr 10
      val anchorBin = peaks[i] and 0x3FF
      var made = 0
      var j = i + 1
      while (j < peakCount && made < PAIR_FANOUT) {
        val dt = (peaks[j] ushr 10) - anchorFrame
        if (dt > MAX_PAIR_DT_FRAMES) break
        if (dt >= 1) {
          if (count == hashes.size) {
            hashes = hashes.copyOf(hashes.size * 2)
            frames = frames.copyOf(frames.size * 2)
          }
          hashes[count] = (anchorBin shl 15) or ((peaks[j] and 0x3FF) shl 6) or dt
          frames[count] = anchorFrame
          count++
          made++
        }
        j++
      }
    }
    return hashes.copyOf(count) to frames.copyOf(count)
  }

  private companion object {
    const val BINS = AcousticFingerprinter.FFT_SIZE / 2

    /** Bins below ~43Hz (DC/rumble) never anchor a landmark. */
    const val MIN_BIN = 4

    /** Neighborhood a peak must strictly dominate: ±2 frames (~93ms) × ±3 bins (~32Hz). */
    const val PEAK_RADIUS_FRAMES = 2
    const val PEAK_RADIUS_BINS = 3
    const val RING_FRAMES = PEAK_RADIUS_FRAMES * 2 + 1

    /**
     * A peak's log-power must exceed its frame's geometric-mean power by
     * [PEAK_PROMINENCE_NATS] *and* sit within [PEAK_DYNAMIC_RANGE_NATS] of the frame's
     * strongest bin. Both are relative to the frame, so a global gain change moves peaks
     * and floors together — the gain-invariance property AcousticFingerprinterTest pins.
     * The dynamic-range bound exists for near-stationary frames (a held tone): their
     * geometric mean is dominated by the quantization-noise floor, which would otherwise
     * let spurious noise maxima through as peaks.
     */
    const val PEAK_PROMINENCE_NATS = 1.5f

    /** ~52dB of power below the frame's strongest bin (1 nat ≈ 4.3dB). */
    const val PEAK_DYNAMIC_RANGE_NATS = 12f

    const val MAX_PEAKS_PER_FRAME = 4
    const val PAIR_FANOUT = 6
    const val MAX_PAIR_DT_FRAMES = 63

    const val POWER_EPS = 1e-12f
  }
}
