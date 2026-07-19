package com.episode6.tacita.audio

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.episode6.tacita.AdFingerprintInfo
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test

class AdFingerprintStoreFileTest {

  private val fingerprinter = AdFingerprinter()
  private val store = AdFingerprintStoreFile()
  private val dir = Files.createTempDirectory("fp-store-test").toFile().apply { deleteOnExit() }
  private val path: Path = dir.resolve("ads.tacita-fp").toOkioPath()

  private val adBreak = fixture("ad-a.mp3") + fixture("ad-b.mp3") + fixture("ad-a.mp3") // ~7.3s
  private val otherBreak = fixture("ad-b.mp3") + fixture("ad-a.mp3") + fixture("ad-b.mp3") // ~8.3s

  private fun fingerprint(
    bytes: ByteArray,
    durationSeconds: Double,
    provenance: AdFingerprintInfo.Provenance = AdFingerprintInfo.Provenance.DIFF_PROVEN,
  ): StoredAdFingerprint = fingerprinter.extract(bytes, 0, bytes.size, durationSeconds, provenance)!!

  @Test fun `reading a missing store returns empty`() {
    assertThat(store.read(path)).isEmpty()
  }

  @Test fun `round-trips fingerprints`() {
    val fps = listOf(fingerprint(adBreak, 7.3), fingerprint(otherBreak, 8.3, AdFingerprintInfo.Provenance.HUMAN_CONFIRMED))

    store.write(path, fps)
    val loaded = store.read(path)

    assertThat(loaded).hasSize(2)
    loaded.zip(fps).forEach { (actual, expected) ->
      assertThat(actual.id).isEqualTo(expected.id)
      assertThat(actual.provenance).isEqualTo(expected.provenance)
      assertThat(actual.durationMs).isEqualTo(expected.durationMs)
      assertThat(actual.totalBytes).isEqualTo(expected.totalBytes)
      assertThat(actual.blockHashes.toList()).isEqualTo(expected.blockHashes.toList())
      assertThat(actual.blockDigests).isEqualTo(expected.blockDigests)
    }
  }

  @Test fun `add dedupes by content id`() {
    assertThat(store.add(path, listOf(fingerprint(adBreak, 7.3)))).isEqualTo(1)
    assertThat(store.add(path, listOf(fingerprint(adBreak, 7.3)))).isEqualTo(0)

    assertThat(store.read(path)).hasSize(1)
  }

  @Test fun `add upgrades diff-proven to human-confirmed but never downgrades`() {
    store.add(path, listOf(fingerprint(adBreak, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)))

    val upgraded = store.add(path, listOf(fingerprint(adBreak, 7.3, AdFingerprintInfo.Provenance.HUMAN_CONFIRMED)))
    assertThat(upgraded, name = "upgrade counts as a change").isEqualTo(1)
    assertThat(store.read(path).single().provenance).isEqualTo(AdFingerprintInfo.Provenance.HUMAN_CONFIRMED)

    val downgraded = store.add(path, listOf(fingerprint(adBreak, 7.3, AdFingerprintInfo.Provenance.DIFF_PROVEN)))
    assertThat(downgraded, name = "downgrade is a no-op").isEqualTo(0)
    assertThat(store.read(path).single().provenance).isEqualTo(AdFingerprintInfo.Provenance.HUMAN_CONFIRMED)
  }

  @Test fun `remove deletes by id`() {
    val fp = fingerprint(adBreak, 7.3)
    store.add(path, listOf(fp, fingerprint(otherBreak, 8.3)))

    assertThat(store.remove(path, fp.id)).isTrue()
    assertThat(store.read(path)).hasSize(1)
    assertThat(store.remove(path, fp.id), name = "second remove finds nothing").isFalse()
  }

  @Test fun `a corrupt store throws instead of returning garbage`() {
    dir.resolve("ads.tacita-fp").writeBytes("not a fingerprint store at all".toByteArray())

    assertFailure { store.read(path) }.isInstanceOf(IOException::class)
  }
}
