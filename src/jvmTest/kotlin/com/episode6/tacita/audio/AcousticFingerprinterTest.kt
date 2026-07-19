package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.episode6.tacita.systemFileSystem
import okio.FileHandle
import okio.Path.Companion.toOkioPath
import okio.use
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test

/**
 * The acoustic layer's core claim — level-invariant cross-encode matching — is pinned with
 * audio synthesized deterministically in-test and re-encoded through jump3r (the pure-java
 * LAME port that also generated the committed stereo.mp3 fixture): a creative fingerprinted
 * from one encode must be found in an episode that embeds the same audio through a
 * *different* encoder run (different bitrate, sample rate and gain — the Simplecast-class
 * serving shape, docs/ALGORITHM.md 2026-07-19), and must not be found where it isn't.
 */
class AcousticFingerprinterTest {

  private val fingerprinter = AcousticFingerprinter()

  @Test fun `extract rejects ranges below the minimum length`() {
    val fp = extract(encodeMp3(synth(seed = 1, seconds = 3.0), kbps = 128))

    assertThat(fp).isNull()
  }

  @Test fun `extract rejects audio without spectral structure`() {
    val silence = FloatArray(10 * 44100)
    // a held tone produces landmarks, but only a degenerate handful of distinct hashes
    // that would "match" any other stationary audio — the distinct-hash floor rejects it
    val tone = FloatArray(10 * 44100) { (0.3 * sin(2.0 * PI * 1000 * it / 44100)).toFloat() }

    assertThat(extract(encodeMp3(silence, kbps = 128)), name = "silence").isNull()
    assertThat(extract(encodeMp3(tone, kbps = 128)), name = "stationary tone").isNull()
  }

  @Test fun `fingerprint ids are content-derived and stable`() {
    val adBytes = encodeMp3(synth(seed = 1, seconds = 8.0), kbps = 128)
    val otherBytes = encodeMp3(synth(seed = 2, seconds = 8.0), kbps = 128)

    val first = extract(adBytes)
    val again = extract(adBytes)
    val other = extract(otherBytes)

    assertThat(first).isNotNull()
    assertThat(other).isNotNull()
    assertThat(again!!.id, name = "same decoded audio, same id").isEqualTo(first!!.id)
    assertThat(other!!.id, name = "different audio, different id").isNotEqualTo(first.id)
  }

  @Test fun `matches a creative across re-encode, resample and gain change`() {
    val ad = synth(seed = 1, seconds = 8.0)
    val fp = extract(encodeMp3(ad, kbps = 128))!!
    val decoy = extract(encodeMp3(synth(seed = 9, seconds = 8.0), kbps = 128))!!
    // the episode embeds the ad's *audio*, then jump3r transcodes the whole thing to a
    // different bitrate, sample rate and gain — no byte of the fingerprinted encode survives
    val episode = encodeMp3(
      pcm = synth(seed = 2, seconds = 20.0) + ad + synth(seed = 3, seconds = 12.0),
      kbps = 64,
      resampleKHz = "22.05",
      scale = 0.7,
    )

    val matches = matchIn(episode, fp, decoy)

    assertThat(matches).hasSize(1)
    assertThat(matches.single().fingerprint.id, name = "matched the embedded creative, not the decoy").isEqualTo(fp.id)
    assertThat(matches.single().fromMs, name = "match starts where the creative was embedded (20s)").isBetween(18500L, 21500L)
    assertThat(matches.single().toMs, name = "match ends where the creative ends (28s)").isBetween(26500L, 29500L)
    assertThat(matches.single().matchedSeconds).isGreaterThanOrEqualTo(AcousticFingerprinter.MIN_MATCH_SECONDS)
  }

  @Test fun `does not match an episode that never contained the creative`() {
    val fp = extract(encodeMp3(synth(seed = 1, seconds = 8.0), kbps = 128))!!
    val episode = encodeMp3(synth(seed = 4, seconds = 30.0), kbps = 64, resampleKHz = "22.05")

    val matches = matchIn(episode, fp)

    assertThat(matches).isEmpty()
  }

  @Test fun `matches every occurrence of a repeated creative`() {
    val ad = synth(seed = 1, seconds = 8.0)
    val fp = extract(encodeMp3(ad, kbps = 128))!!
    // the same creative at 10s and 28s — the DAI shape that rotates one campaign through
    // multiple slots
    val episode = encodeMp3(
      pcm = synth(seed = 5, seconds = 10.0) + ad + synth(seed = 6, seconds = 10.0) + ad + synth(seed = 7, seconds = 10.0),
      kbps = 64,
      scale = 0.8,
    )

    val matches = matchIn(episode, fp)

    assertThat(matches).hasSize(2)
    assertThat(matches[0].fromMs).isBetween(8500L, 11500L)
    assertThat(matches[1].fromMs).isBetween(26500L, 29500L)
  }

  @Test fun `landmarks are invariant under gain scaling`() {
    val ad = synth(seed = 1, seconds = 8.0)

    val full = landmarksOf(ad, gain = 1f)
    val halved = landmarksOf(ad, gain = 0.5f)

    val fullSet = HashSet<Long>().apply {
      for (i in full.hashes.indices) add((full.hashes[i].toLong() shl 32) or full.frames[i].toLong())
    }
    var shared = 0
    for (i in halved.hashes.indices) {
      if (((halved.hashes[i].toLong() shl 32) or halved.frames[i].toLong()) in fullSet) shared++
    }
    val overlap = shared.toDouble() / maxOf(full.hashes.size, halved.hashes.size)
    // not 1.0: a non-power-of-two gain rounds differently through the float pipeline, so a
    // borderline peak can flip at a threshold boundary — invariance means the constellation
    // survives, not that every landmark does
    assertThat(overlap, name = "landmark overlap between 1.0x and 0.5x gain").isGreaterThanOrEqualTo(0.95)
  }

  @Test fun `matches a creative stitched between independently-encoded segments`() {
    val ad = synth(seed = 1, seconds = 8.0)
    val fp = extract(encodeMp3(ad, kbps = 128))!!
    // byte-level concatenation of three separate encoder runs — exactly how stitching
    // hosts assemble a serving; the decoder must track the creative across segment joins
    val serving = encodeMp3(synth(seed = 2, seconds = 12.0), kbps = 64) +
      encodeMp3(ad, kbps = 64, scale = 0.8) +
      encodeMp3(synth(seed = 3, seconds = 12.0), kbps = 64)

    val matches = matchIn(serving, fp)

    assertThat(matches).hasSize(1)
    assertThat(matches.single().fromMs, name = "match starts at the stitched segment (12s)").isBetween(10500L, 13500L)
    assertThat(matches.single().matchedSeconds).isGreaterThanOrEqualTo(AcousticFingerprinter.MIN_MATCH_SECONDS)
  }

  private fun landmarksOf(pcm: FloatArray, gain: Float): AcousticLandmarkSession.Landmarks {
    val session = AcousticLandmarkSession()
    val scaled = FloatArray(pcm.size) { pcm[it] * gain }
    session.feed(scaled, 0, scaled.size, channels = 1, hz = 44100)
    return session.finish()
  }

  private fun extract(bytes: ByteArray): AcousticFingerprint? = withHandle(bytes) { fingerprinter.extract(it) }

  private fun matchIn(bytes: ByteArray, vararg fingerprints: AcousticFingerprint): List<AcousticFingerprinter.Match> =
    withHandle(bytes) { fingerprinter.match(it, fingerprints.toList()) }

  private fun <T> withHandle(bytes: ByteArray, block: (FileHandle) -> T): T {
    val file = tempFile(".mp3").apply { writeBytes(bytes) }
    return systemFileSystem.openReadOnly(file.toOkioPath()).use(block)
  }

  private fun tempFile(suffix: String): File =
    File.createTempFile("acoustic-test", suffix).apply { deleteOnExit() }
}
