package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Independent-implementation check of the minimp3 port (jvm-only — JLayer is a java
 * library): JLayer must agree on stream structure exactly (sample counts, rate, channels)
 * and on PCM loosely. JLayer's synthesis has known limited accuracy (measured: ~6 LSB rms
 * on the mono fixtures, ~0.015 peak on stereo transients), so this only guards against
 * gross structural bugs; the digest pin in the common [Mp3DecoderTest] is the precise one.
 */
class Mp3DecoderReferenceTest {

  @Test fun `mpeg2 mono fixture matches reference decoder`() {
    compareAgainstReference(fixture("single.mp3"))
  }

  @Test fun `stitched multi-segment fixture matches reference decoder`() {
    compareAgainstReference(fixture("stitched.mp3"))
  }

  @Test fun `mpeg1 joint stereo fixture matches reference decoder`() {
    compareAgainstReference(fixture("stereo.mp3"))
  }

  private fun referenceDecodeAll(data: ByteArray): DecodedPcm {
    val bitstream = Bitstream(ByteArrayInputStream(data))
    val decoder = Decoder()
    val out = ArrayList<FloatArray>()
    var total = 0
    var hz = 0
    var channels = 0
    while (true) {
      val header = bitstream.readFrame() ?: break
      val sb = decoder.decodeFrame(header, bitstream) as SampleBuffer
      hz = sb.sampleFrequency
      channels = sb.channelCount
      val chunk = FloatArray(sb.bufferLength) { sb.buffer[it] / 32768f }
      out.add(chunk)
      total += chunk.size
      bitstream.closeFrame()
    }
    val pcm = FloatArray(total)
    var w = 0
    out.forEach { it.copyInto(pcm, w); w += it.size }
    return DecodedPcm(pcm, hz, channels)
  }

  private fun compareAgainstReference(data: ByteArray) {
    val ours = decodeAll(data)
    val ref = referenceDecodeAll(data)

    assertThat(ours.hz).isEqualTo(ref.hz)
    assertThat(ours.channels).isEqualTo(ref.channels)
    assertThat(ours.pcm.size).isEqualTo(ref.pcm.size)
    assertThat(ours.pcm.size).isGreaterThan(0)

    var maxDiff = 0.0
    var sumSq = 0.0
    for (i in ours.pcm.indices) {
      // JLayer's 16-bit output clamps at full scale; our float output doesn't (the stereo
      // fixture legitimately peaks at ~1.09) — compare in the domain both can represent
      val clamped = ours.pcm[i].coerceIn(-1f, 32767f / 32768f)
      val d = (clamped - ref.pcm[i]).toDouble()
      maxDiff = maxOf(maxDiff, abs(d))
      sumSq += d * d
    }
    val rms = sqrt(sumSq / ours.pcm.size)
    assertThat(maxDiff).isLessThan(MAX_SAMPLE_DIFF)
    assertThat(rms).isLessThan(MAX_RMS_DIFF)
  }
}

/** Bounds on JLayer's inaccuracy, not ours — see the class KDoc; the digest test is exact. */
private const val MAX_SAMPLE_DIFF = 0.03
private const val MAX_RMS_DIFF = 1e-3
