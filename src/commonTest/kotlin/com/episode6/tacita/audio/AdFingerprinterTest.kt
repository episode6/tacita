package com.episode6.tacita.audio

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.episode6.tacita.AdFingerprintInfo
import com.episode6.tacita.systemFileSystem
import com.episode6.tacita.testTempFile
import okio.use
import kotlin.test.Test

class AdFingerprinterTest {

  private val fingerprinter = AdFingerprinter()
  private val parser = Mp3SegmentParser()

  private val contentA = fixture("content-a.mp3") // ~6.1s
  private val contentB = fixture("content-b.mp3") // ~8.1s
  private val adA = fixture("ad-a.mp3") // ~2.1s
  private val adB = fixture("ad-b.mp3") // ~3.1s

  // ~7.3s / ~3 whole 4KB blocks: comfortably above the 5s fingerprint and match floors
  private val adBreak = adA + adB + adA

  @Test fun `extract rejects ranges below the minimum length`() {
    val fp = fingerprinter.extract(
      data = adA,
      fromByte = 0,
      toByte = adA.size,
      durationSeconds = 2.1,
      provenance = AdFingerprintInfo.Provenance.DIFF_PROVEN,
    )

    assertThat(fp).isNull()
  }

  @Test fun `extracted fingerprint ids are content-derived and stable`() {
    val composed = contentA + adBreak + contentB
    val standalone = fingerprinter.extract(adBreak, 0, adBreak.size, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)
    val inContext = fingerprinter.extract(composed, contentA.size, contentA.size + adBreak.size, 7.3, AdFingerprintInfo.Provenance.HUMAN_CONFIRMED)

    assertThat(standalone).isNotNull()
    assertThat(inContext).isNotNull()
    assertThat(inContext!!.id, name = "same bytes, same id, regardless of position or provenance").isEqualTo(standalone!!.id)
  }

  @Test fun `matches a stored creative at a different position in a different file`() {
    val fp = fingerprinter.extract(adBreak, 0, adBreak.size, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)!!

    val matches = matchIn(contentB + adBreak + contentA, fp)

    assertThat(matches).hasSize(1)
    assertThat(matches.single().fromByte, name = "match starts at the break").isEqualTo(contentB.size)
    assertThat(matches.single().matchedSeconds).isGreaterThanOrEqualTo(5.0)
  }

  @Test fun `matches every occurrence of a repeated creative`() {
    val fp = fingerprinter.extract(adBreak, 0, adBreak.size, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)!!

    val matches = matchIn(adBreak + contentA + adBreak + contentB, fp)

    assertThat(matches).hasSize(2)
    assertThat(matches[0].fromByte).isEqualTo(0)
    assertThat(matches[1].fromByte).isEqualTo(adBreak.size + contentA.size)
  }

  @Test fun `finds nothing when the creative is absent`() {
    val fp = fingerprinter.extract(adBreak, 0, adBreak.size, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)!!

    assertThat(matchIn(contentA + contentB, fp)).isEmpty()
  }

  @Test fun `a partial occurrence below the match floor is not reported`() {
    val fp = fingerprinter.extract(adBreak, 0, adBreak.size, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)!!

    // only the first ~5.2s (~2 whole blocks ≈ 4s of verified run) of the break is present
    val matches = matchIn(contentA + (adA + adB) + contentB, fp)

    assertThat(matches).isEmpty()
  }

  @Test fun `edge slop in the stored range still matches the creative interior`() {
    // a sloppy human confirmation that dragged in ~2KB of episode-unique bytes on each side
    val bigBreak = adA + adB + adA + adB // ~10.4s, 5 whole blocks
    val sloppyFrom = contentA.size - 2048
    val sloppyTo = contentA.size + bigBreak.size + 2048
    val composed = contentA + bigBreak + contentB
    val fp = fingerprinter.extract(composed, sloppyFrom, sloppyTo, 10.4 + 2.0, AdFingerprintInfo.Provenance.HUMAN_CONFIRMED)!!

    // a different episode: same creative, different surrounding content
    val matches = matchIn(contentB + bigBreak + contentA, fp)

    assertThat(matches).hasSize(1)
    val match = matches.single()
    assertThat(match.fromByte, name = "match must stay inside the creative").isBetween(contentB.size - 2048, contentB.size + RollingHash.BLOCK_BYTES + 2048)
    assertThat(match.matchedSeconds).isGreaterThanOrEqualTo(5.0)
  }

  @Test fun `secondsAtBytes maps match edges into the playback timeline`() {
    val composed = contentB + adBreak + contentA
    val file = testTempFile("fingerprint-test", composed)
    val seconds = systemFileSystem.openReadOnly(file).use { handle ->
      parser.secondsAtBytes(handle, listOf(0, contentB.size, composed.size))
    }

    assertThat(seconds[0]).isEqualTo(0.0)
    assertThat(seconds[1], name = "break starts after content-b (~8.1s)").isBetween(7.6, 8.6)
    assertThat(seconds[2], name = "end of file (~21.5s)").isBetween(20.5, 22.5)
  }

  private fun matchIn(composition: ByteArray, vararg fingerprints: StoredAdFingerprint): List<AdFingerprinter.Match> {
    val file = testTempFile("fingerprint-test", composition)
    return systemFileSystem.openReadOnly(file).use { handle ->
      fingerprinter.match(handle, fingerprints.toList())
    }
  }
}
