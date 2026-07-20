package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.isLessThan
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

class FftTest {

  @Test fun `matches a naive dft on random input`() {
    val n = 1024
    val random = Random(42)
    val re = FloatArray(n) { random.nextFloat() * 2 - 1 }
    val im = FloatArray(n) { random.nextFloat() * 2 - 1 }
    val (expectedRe, expectedIm) = naiveDft(re, im)

    Fft(n).transform(re, im)

    var maxDiff = 0.0
    for (k in 0 until n) {
      maxDiff = maxOf(maxDiff, abs(re[k] - expectedRe[k]), abs(im[k] - expectedIm[k]))
    }
    assertThat(maxDiff).isLessThan(1e-2)
  }

  @Test fun `resolves a pure tone into its bin`() {
    val n = 1024
    val bin = 37
    val re = FloatArray(n) { cos(2.0 * PI * bin * it / n).toFloat() }
    val im = FloatArray(n)

    Fft(n).transform(re, im)

    // energy concentrates in bins 37 and n-37 (real input), n/2 amplitude each
    assertThat(abs(re[bin] - n / 2.0)).isLessThan(1e-2)
    for (k in 0 until n / 2) {
      if (k == bin) continue
      assertThat(abs(re[k].toDouble()), name = "leakage at bin $k").isLessThan(1e-2)
      assertThat(abs(im[k].toDouble()), name = "leakage at bin $k").isLessThan(1e-2)
    }
  }

  private fun naiveDft(re: FloatArray, im: FloatArray): Pair<DoubleArray, DoubleArray> {
    val n = re.size
    val outRe = DoubleArray(n)
    val outIm = DoubleArray(n)
    for (k in 0 until n) {
      var sr = 0.0
      var si = 0.0
      for (t in 0 until n) {
        val angle = -2.0 * PI * k * t / n
        val c = cos(angle)
        val s = sin(angle)
        sr += re[t] * c - im[t] * s
        si += re[t] * s + im[t] * c
      }
      outRe[k] = sr
      outIm[k] = si
    }
    return outRe to outIm
  }
}
