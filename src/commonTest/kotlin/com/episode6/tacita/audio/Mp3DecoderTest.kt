package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import okio.Buffer
import kotlin.random.Random
import kotlin.test.Test

/**
 * Verifies the minimp3 port against output verified bit-for-bit against the C minimp3
 * (scalar, float, MP3-only — the exact configuration ported) at port time, 2026-07-19.
 * Every sample of all three streams matched the C decoder; the digests below pin that.
 * The decode path uses only IEEE-754 single arithmetic (no libm), so the bits are
 * deterministic across platforms — which is exactly why this test runs on every target.
 *
 * A second, independent-implementation check against JLayer lives in the jvm-only
 * [Mp3DecoderReferenceTest] (JLayer is a java library).
 *
 * Fixtures: single/stitched are MPEG-2 22.05kHz mono. stereo.mp3 is MPEG-1 44.1kHz joint
 * stereo, 128kbps CBR (the common podcast serving shape — exercises the two-granule,
 * mid/side and short-block paths): 2s of per-channel tones plus periodic noise bursts,
 * generated with jump3r 1.0.5 (LAME 3.98 port), seed 1234.
 */
class Mp3DecoderTest {

  @Test fun `decoded pcm is bit-identical to the C minimp3 verification run`() {
    assertThat(decodeAll(fixture("single.mp3")).sha256()).isEqualTo(
      "a2507081c60289192f12236793d5ac4714a4763e3f3961ebf1fe260b00a0f80c",
    )
    assertThat(decodeAll(fixture("stitched.mp3")).sha256()).isEqualTo(
      "5bcd2353f5ba6d1e07071f4b223c0a152a3454911e3fbc0ee7959c9ee60fc0bd",
    )
    assertThat(decodeAll(fixture("stereo.mp3")).sha256()).isEqualTo(
      "e65954898fab16af4012b3aa2bb80f3ef30128359b1efc60bc5964e8ac9bf075",
    )
  }

  @Test fun `reports stream metadata`() {
    val decoder = Mp3Decoder()
    val data = fixture("single.mp3")
    val pcm = FloatArray(Mp3Decoder.MAX_SAMPLES_PER_FRAME)

    var pos = 0
    var samples = 0
    while (samples == 0) {
      samples = decoder.decodeFrame(data, pos, data.size - pos, pcm)
      check(decoder.info.frameBytes > 0) { "ran out of data before decoding a frame" }
      pos += decoder.info.frameBytes
    }

    assertThat(decoder.info.hz).isEqualTo(22050)
    assertThat(decoder.info.channels).isEqualTo(1)
    assertThat(decoder.info.layer).isEqualTo(3)
    assertThat(decoder.info.bitrateKbps).isEqualTo(56)
    assertThat(samples).isEqualTo(576) // MPEG-2: one 576-sample granule per frame
  }

  @Test fun `garbage input yields no samples and terminates`() {
    val garbage = ByteArray(64 * 1024).also { Random(42).nextBytes(it) }

    val decoded = decodeAll(garbage)

    assertThat(decoded.pcm.size).isEqualTo(0)
  }

  @Test fun `truncated stream decodes the complete frames and stops`() {
    val full = decodeAll(fixture("single.mp3"))
    val truncated = decodeAll(fixture("single.mp3").let { it.copyOf(it.size / 2) })

    assertThat(truncated.pcm.size).isGreaterThan(0)
    assertThat(truncated.pcm.size).isLessThan(full.pcm.size)
  }

  @Test fun `reset makes an instance reusable across streams`() {
    val data = fixture("stereo.mp3")
    val decoder = Mp3Decoder()

    val first = decodeAll(data, decoder)
    decoder.reset()
    val second = decodeAll(data, decoder)

    assertThat(second.pcm.contentEquals(first.pcm)).isEqualTo(true)
  }
}

// shared with the jvm-only Mp3DecoderReferenceTest

internal class DecodedPcm(val pcm: FloatArray, val hz: Int, val channels: Int) {
  fun sha256(): String {
    val buffer = Buffer()
    pcm.forEach { buffer.writeIntLe(it.toRawBits()) }
    return buffer.readByteString().sha256().hex()
  }
}

internal fun decodeAll(data: ByteArray, decoder: Mp3Decoder = Mp3Decoder()): DecodedPcm {
  val frame = FloatArray(Mp3Decoder.MAX_SAMPLES_PER_FRAME)
  val out = ArrayList<FloatArray>()
  var total = 0
  var hz = 0
  var channels = 0
  var pos = 0
  while (pos < data.size) {
    val samples = decoder.decodeFrame(data, pos, data.size - pos, frame)
    if (samples > 0) {
      hz = decoder.info.hz
      channels = decoder.info.channels
      out.add(frame.copyOf(samples * channels))
      total += samples * channels
    }
    if (decoder.info.frameBytes == 0) break
    pos += decoder.info.frameBytes
  }
  val pcm = FloatArray(total)
  var w = 0
  out.forEach { it.copyInto(pcm, w); w += it.size }
  return DecodedPcm(pcm, hz, channels)
}
