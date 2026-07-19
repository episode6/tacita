package com.episode6.tacita.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT, sized once and reused per STFT frame. Exists for
 * the acoustic ad-fingerprint layer ([AcousticFingerprinter]) — no FFT is available in
 * common Kotlin across the KMP targets. Twiddles and the bit-reversal permutation are
 * precomputed at construction; [transform] allocates nothing.
 */
internal class Fft(private val size: Int) {

  private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
  private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
  private val reversed = IntArray(size).also { rev ->
    var bits = 0
    while ((1 shl bits) < size) bits++
    require(1 shl bits == size) { "fft size must be a power of two: $size" }
    for (i in 0 until size) {
      var r = 0
      for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
      rev[i] = r
    }
  }

  /** Transforms `re`/`im` (each of length [size]) in place. */
  fun transform(re: FloatArray, im: FloatArray) {
    require(re.size == size && im.size == size) { "expected arrays of size $size" }
    for (i in 0 until size) {
      val j = reversed[i]
      if (j > i) {
        var t = re[i]; re[i] = re[j]; re[j] = t
        t = im[i]; im[i] = im[j]; im[j] = t
      }
    }
    var half = 1
    while (half < size) {
      val step = size / (half * 2)
      for (group in 0 until size step half * 2) {
        var k = 0
        for (pair in group until group + half) {
          val wr = cosTable[k]
          val wi = -sinTable[k]
          val hi = pair + half
          val tr = re[hi] * wr - im[hi] * wi
          val ti = re[hi] * wi + im[hi] * wr
          re[hi] = re[pair] - tr
          im[hi] = im[pair] - ti
          re[pair] += tr
          im[pair] += ti
          k += step
        }
      }
      half *= 2
    }
  }
}
